package fr.smartenergy.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** SunSpec Modbus/TCP READ-ONLY discovery: FC03 only, no registers written. */
data class SunSpecModel(val modelId:Int, val dataLength:Int, val headerRegister:Int)
data class SunSpecDiscovery(val baseRegister:Int, val models:List<SunSpecModel>)

object SunSpecReadOnly {
    // SunSpec map may start at PDU register 0, 40000, or 50000.
    val BASES = listOf(40000,50000,0)
    fun isPrivateIPv4(host:String):Boolean {
        val p=host.split(".").map { it.toIntOrNull() ?: return false }
        if(p.size!=4 || p.any {it !in 0..255})return false
        return p[0]==10 || (p[0]==192 && p[1]==168) ||
               (p[0]==172 && p[1] in 16..31)
    }
    fun isSunS(words:IntArray):Boolean =
        words.size>=2 && words[0]==0x5375 && words[1]==0x6e53

    fun discoverModels(base:Int, read:(Int,Int)->IntArray):SunSpecDiscovery {
        require(base in BASES)
        val signature=read(base,4)
        require(signature.size==4 && isSunS(signature)) { "Signature SunS absente" }
        var index=base+2
        val models=mutableListOf<SunSpecModel>()
        repeat(48) {
            val header=read(index,2)
            if(header.size!=2)throw IllegalArgumentException("En-tête SunSpec incomplet")
            val id=header[0];val size=header[1]
            if(id==0xffff && size==0)return SunSpecDiscovery(base,models)
            if(id !in 1..0xfffe || size !in 1..2048 ||
                index+size+2>65536)throw IllegalArgumentException("Chaîne de modèles invalide")
            models+=SunSpecModel(id,size,index)
            index+=2+size
        }
        throw IllegalArgumentException("Chaîne SunSpec non terminée")
    }

    fun discover(host:String, port:Int=502, unitId:Int=1):SunSpecDiscovery {
        require(isPrivateIPv4(host)) { "Adresse IP LAN privée obligatoire" }
        require(port in 1..65535 && unitId in 0..247)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host,port),2500)
            socket.soTimeout=3500
            val input=DataInputStream(socket.getInputStream())
            val output=DataOutputStream(socket.getOutputStream())
            var transaction=0
            fun readWords(offset:Int,count:Int):IntArray {
                require(offset in 0..65535 && count in 1..125 && offset+count<=65536)
                transaction=(transaction+1) and 0xffff
                // MBAP tx(2), protocol(2), length(2), unit(1), PDU FC03+addr+qty
                output.writeShort(transaction)
                output.writeShort(0)
                output.writeShort(6)
                output.writeByte(unitId)
                output.writeByte(3)
                output.writeShort(offset)
                output.writeShort(count)
                output.flush()
                val responseTx=input.readUnsignedShort()
                val protocol=input.readUnsignedShort()
                val length=input.readUnsignedShort()
                val unit=input.readUnsignedByte()
                if(responseTx!=transaction || protocol!=0 || unit!=unitId ||
                    length !in 3..253)throw IllegalArgumentException("MBAP invalide")
                val code=input.readUnsignedByte()
                if(code==0x83) {
                    val exception=input.readUnsignedByte()
                    throw IllegalArgumentException("Exception Modbus FC03 : $exception")
                }
                if(code!=3)throw IllegalArgumentException("Réponse non FC03 rejetée")
                val byteCount=input.readUnsignedByte()
                if(length!=3+byteCount || byteCount!=2*count)
                    throw IllegalArgumentException("Taille de réponse Modbus incorrecte")
                return IntArray(count){input.readUnsignedShort()}
            }
            for(base in BASES){
                try {
                    val signature=readWords(base,4)
                    if(!isSunS(signature))continue
                    return discoverModels(base,::readWords)
                }catch(error:IllegalArgumentException) {
                    // FC03 exception is expected when a base is not mapped.
                    if(base==BASES.last())throw error
                }
            }
            throw IllegalArgumentException("Aucun modèle SunSpec trouvé")
        }
    }
}
