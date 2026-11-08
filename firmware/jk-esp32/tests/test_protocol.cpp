#include "../src/jk_protocol.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <vector>
using namespace smartjk;

static void set16(uint8_t *b, unsigned offset, uint16_t value) {
  b[offset] = value & 0xFF; b[offset + 1] = value >> 8;
}
static void set32(uint8_t *b, unsigned offset, uint32_t value) {
  b[offset] = value & 0xFF; b[offset + 1] = (value >> 8) & 0xFF;
  b[offset + 2] = (value >> 16) & 0xFF; b[offset + 3] = (value >> 24) & 0xFF;
}
static void checksum(uint8_t *b) {
  unsigned sum = 0;
  for (int i = 0; i < 299; ++i) sum = (sum + b[i]) & 0xFF;
  b[299] = sum;
}
static void sample24(uint8_t *b) {
  memset(b, 0, 300);
  b[0] = 0x55; b[1] = 0xaa; b[2] = 0xeb; b[3] = 0x90;
  b[4] = 2; b[5] = 8;
  for (int i = 0; i < 16; ++i) set16(b, 6 + i * 2, 3300);
  set32(b, 54, 0xffff);
  set32(b, 118, 52800);
  set32(b, 126, uint32_t(int32_t(-10000)));
  set16(b, 130, 250);
  set16(b, 132, 260);
  set16(b, 134, 270);
  b[141] = 73;
  set32(b, 146, 280000);
  set32(b, 150, 35);
  b[166] = 1; b[167] = 1;
  checksum(b);
}
static void sample32(uint8_t *b) {
  memset(b, 0, 300);
  b[0] = 0x55; b[1] = 0xaa; b[2] = 0xeb; b[3] = 0x90;
  b[4] = 2; b[5] = 2;
  for (int i = 0; i < 16; ++i) set16(b, 6 + i * 2, 3330);
  set32(b, 70, 0xffff);
  set32(b, 150, 53280);
  set32(b, 158, 3000);
  set16(b, 162, uint16_t(int16_t(-2000)));
  set16(b, 164, uint16_t(int16_t(-2000)));
  set16(b, 144, uint16_t(int16_t(-2000)));
  b[173] = 48;
  set32(b, 178, 314000);
  set32(b, 182, 49);
  b[198] = 1; b[199] = 0;
  checksum(b);
}
int main() {
  uint8_t first[300];
  sample24(first);
  assert(frameValid(first));
  Reading reading{};
  assert(decodeFrame(first, reading));
  assert(reading.valid && reading.layout == 24 && reading.cellCount == 16);
  assert(reading.soc == 73 && fabsf(reading.voltageV - 52.8f) < 0.01f);
  assert(fabsf(reading.currentA + 10.0f) < 0.01f);
  assert(reading.tempAvailable[0] && reading.tempAvailable[1] && reading.tempAvailable[2]);
  assert(!reading.tempSafetyVerified);
  assert(reading.cycles == 35 && reading.chargingMos && reading.dischargingMos);
  assert(reading.cellMinNumber == 1 && reading.cellMaxNumber == 1);
  // Multiple cells share the minimum/maximum to the mV, yet exactly one
  // physical index must be chosen, as in the author's JK Bluetooth module.
  uint8_t tied[300];
  memcpy(tied, first, 300);
  set16(tied, 6 + 0 * 2, 3298);
  set16(tied, 6 + 1 * 2, 3298);
  set16(tied, 6 + 7 * 2, 3302);
  set16(tied, 6 + 8 * 2, 3302);
  checksum(tied);
  Reading extrema{};
  assert(decodeFrame(tied, extrema));
  assert(extrema.cellMinNumber == 1 && extrema.cellMaxNumber == 8);
  // When JK firmware provides min/max slot IDs, trust one index rather than
  // coloring every cell sharing the same 0.001 V rounded voltage.
  tied[62] = 9; // BMS reports cell 9 as max (cell 8 ties)
  tied[63] = 2; // BMS reports cell 2 as min (cell 1 ties)
  checksum(tied);
  assert(decodeFrame(tied, extrema));
  assert(extrema.cellMinNumber == 2 && extrema.cellMaxNumber == 9);
  tied[63] = 42; // invalid BMS index -> deterministic fallback
  tied[62] = 1;  // invalid mismatch (cell1 is minimum)
  checksum(tied);
  assert(decodeFrame(tied, extrema));
  assert(extrema.cellMinNumber == 1 && extrema.cellMaxNumber == 8);
  // Restore absent native metadata before sparse-slot regression.
  tied[62] = 0; tied[63] = 0;
  checksum(tied);
  assert(fabsf(extrema.cellMinV - 3.298f) < 0.001f);
  assert(fabsf(extrema.cellMaxV - 3.302f) < 0.001f);
  // Sparse JK physical slots must not be renumbered when reporting extrema.
  set32(tied, 54, 0xfffe); // remove physical cell 1 from the 24S mask
  set32(tied, 118, 49502); // sum of all active millivolts
  checksum(tied);
  assert(decodeFrame(tied, extrema));
  assert(extrema.cellMinNumber == 2 && extrema.cellMaxNumber == 8);
  Assembler stream{};
  Reading assembled{};
  assert(!stream.append(first, 1, assembled));
  assert(!stream.append(first + 1, 46, assembled));
  assert(stream.append(first + 47, 253, assembled));
  assert(assembled.soc == 73 && stream.validFrames == 1);
  uint8_t broken[300];
  memcpy(broken, first, sizeof broken);
  broken[299] ^= 0xFF;
  assert(!stream.append(broken, 300, assembled));
  assert(stream.badCrc >= 1);
  uint8_t second[300];
  sample32(second);
  assert(frameValid(second));
  assert(decodeFrame(second, reading));
  assert(reading.layout == 32 && reading.cellCount == 16 && reading.soc == 48);
  assert(reading.cellMinNumber == 1 && reading.cellMaxNumber == 1);
  assert(!reading.tempAvailable[0] && !reading.tempAvailable[1] && !reading.tempAvailable[2]);
  assert(!reading.tempSafetyVerified);
  assert(fabsf(reading.voltageV - 53.28f) < 0.01f);
  // Faulty cell voltage must not be presented as a valid reading.
  uint8_t invalid[300];
  memcpy(invalid, first, sizeof invalid);
  set16(invalid, 6, 6000);
  checksum(invalid);
  assert(frameValid(invalid));
  assert(!decodeFrame(invalid, reading));
  stream.reset();
  uint8_t two[600];
  memcpy(two, first, 300);
  memcpy(two + 300, second, 300);
  assert(stream.append(two, sizeof two, assembled));
  assert(assembled.layout == 32 && stream.validFrames == 2);
  // Four independent streams with interleaved fragments must never mix.
  Assembler parallel[4];
  uint8_t packets[4][300];
  Reading perDevice[4] = {};
  for (int i=0;i<4;++i) {
    memcpy(packets[i],first,sizeof first);
    packets[i][141]=(uint8_t)(60+i); // JK02_24S SOC offset
    checksum(packets[i]);
    assert(!parallel[i].append(packets[i],47,perDevice[i]));
  }
  for (int i=3;i>=0;--i) {
    assert(parallel[i].append(packets[i]+47,253,perDevice[i]));
    assert(parallel[i].validFrames==1);
    assert(perDevice[i].soc==60+i);
  }
  printf("JK02 4-stream interleaved framing regression passed\n");
  printf("JK02 24S/32S parser + CRC + fragmentation tests passed\n");
}
