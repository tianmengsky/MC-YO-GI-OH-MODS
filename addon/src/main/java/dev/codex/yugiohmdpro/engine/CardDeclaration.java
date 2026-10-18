package dev.codex.yugiohmdpro.engine;

import java.nio.*;

/** Percy's MSG_ANNOUNCE_CARD reverse Polish predicate, using the verified 76-byte card ABI. */
final class CardDeclaration {
    private CardDeclaration() {}
    static boolean accepts(byte[] data,int[] program) {
        ByteBuffer card=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int code=card.getInt(0),type=card.getInt(40);
        if(code!=78734254 && code!=13857930 && (card.getInt(4)!=0 || (type&0x4001)==0x4001))return false;
        int[] stack=new int[program.length+1];int n=0;
        for(int op:program) {
            if(op>=0x40000000 && op<=0x40000005) {
                if(n<2)return false;int rhs=stack[--n],lhs=stack[--n];
                stack[n++]=switch(op) {
                    case 0x40000000 -> lhs+rhs;case 0x40000001 -> lhs-rhs;
                    case 0x40000002 -> lhs*rhs;case 0x40000003 -> rhs==0?0:lhs/rhs;
                    case 0x40000004 -> lhs!=0 && rhs!=0?1:0;default -> lhs!=0 || rhs!=0?1:0;
                };
            } else if(op==0x40000006 || op==0x40000007 || op>=0x40000100 && op<=0x40000104) {
                if(n<1)return false;int value=stack[--n];
                stack[n++]=switch(op) {
                    case 0x40000006 -> -value;case 0x40000007 -> value==0?1:0;
                    case 0x40000100 -> code==value?1:0;
                    case 0x40000101 -> setcode(card,value)?1:0;
                    case 0x40000102 -> type&value;
                    case 0x40000103 -> card.getInt(52)&value;
                    default -> card.getInt(48)&value;
                };
            } else stack[n++]=op;
        }
        return n==1 && stack[0]!=0;
    }
    private static boolean setcode(ByteBuffer data,int value) {
        for(int at=8;at<40;at+=2) {
            int set=Short.toUnsignedInt(data.getShort(at));
            if(set!=0 && (set&0xfff)==(value&0xfff) && (set&value&0xf000)==(value&0xf000))return true;
        }
        return false;
    }
}
