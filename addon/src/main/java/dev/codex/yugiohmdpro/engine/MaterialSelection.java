package dev.codex.yugiohmdpro.engine;

import java.util.BitSet;
import java.util.*;

/** Shared by the authoritative response encoder and the GUI; values encode two alternatives. */
public final class MaterialSelection {
    private MaterialSelection() {}
    public static boolean valid(int[] weights,int target,int mode) {
        if(weights.length==0 || target<=0)return false;
        if(mode!=0) {
            long low=0,high=0;int smallest=Integer.MAX_VALUE;
            for(int weight:weights) {
                int a=weight&65535,b=weight>>>16,min=b==0?a:Math.min(a,b);
                low+=min;high+=Math.max(a,b);smallest=Math.min(smallest,min);
            }
            return high>=target && low-smallest<target;
        }
        BitSet sums=new BitSet(target+1);sums.set(0);
        for(int weight:weights) {
            int a=weight&65535,b=weight>>>16;BitSet next=new BitSet(target+1);
            for(int sum=sums.nextSetBit(0);sum>=0;sum=sums.nextSetBit(sum+1)) {
                if(sum+a<=target)next.set(sum+a);
                if(b!=0 && sum+b<=target)next.set(sum+b);
            }
            sums=next;if(sums.isEmpty())return false;
        }
        return sums.get(target);
    }
    /** Exact subset solver with predecessor states; no arbitrary DFS depth or search budget. */
    public static int[] select(int[] weights,int mandatory,int min,int max,int target,int mode) {
        max=Math.min(max,Math.min(weights.length-mandatory,63-mandatory));
        if(target<=0 || target>65535 || max<min)return null;
        if(mode!=0) {
            long low=0,high=0;int smallest=Integer.MAX_VALUE;
            for(int i=0;i<mandatory;i++) {int a=weights[i]&65535,b=weights[i]>>>16,m=b==0?a:Math.min(a,b);low+=m;high+=Math.max(a,b);smallest=Math.min(smallest,m);}
            var optional=new ArrayList<Integer>();for(int i=mandatory;i<weights.length;i++)optional.add(i);
            optional.sort(Comparator.comparingInt((Integer i)->low(weights[i])));
            return atLeast(weights,optional,0,new ArrayList<>(),min,max,target,low,high,smallest);
        }
        BitSet roots=new BitSet(target+1);roots.set(0);
        for(int i=0;i<mandatory;i++) {
            BitSet next=new BitSet(target+1);int a=weights[i]&65535,b=weights[i]>>>16;
            for(int sum=roots.nextSetBit(0);sum>=0;sum=roots.nextSetBit(sum+1)) {
                if(sum+a<=target)next.set(sum+a);if(b!=0 && sum+b<=target)next.set(sum+b);
            }
            roots=next;
        }
        if(roots.isEmpty())return null;
        BitSet[] reached=new BitSet[max+1];int[][] parents=new int[max+1][];
        for(int n=0;n<=max;n++) {reached[n]=new BitSet(target+1);parents[n]=new int[target+1];Arrays.fill(parents[n],-1);}
        reached[0]=roots;
        if(min==0 && roots.get(target))return new int[0];
        for(int index=mandatory;index<weights.length;index++) {
            int a=weights[index]&65535,b=weights[index]>>>16;
            for(int n=Math.min(max,index-mandatory+1);n>=1;n--) {
                for(int sum=reached[n-1].nextSetBit(0);sum>=0;sum=reached[n-1].nextSetBit(sum+1)) {
                    for(int option=0;option<2;option++) {
                        int contribution=option==0?a:b;if(option==1 && b==0)continue;
                        if(sum+contribution<=target && !reached[n].get(sum+contribution)) {
                        reached[n].set(sum+contribution);parents[n][sum+contribution]=(index<<16)|sum;
                        }
                    }
                }
                if(n>=min && reached[n].get(target)) {
                    int[] result=new int[n];int total=target;
                    for(int count=n;count>0;count--) {int parent=parents[count][total];result[count-1]=parent>>>16;total=parent&65535;}
                    return result;
                }
            }
        }
        return null;
    }
    private static int low(int weight) {int a=weight&65535,b=weight>>>16;return b==0?a:Math.min(a,b);}
    private static int[] atLeast(int[] weights,List<Integer> optional,int at,List<Integer> chosen,int min,int max,int target,long low,long high,int smallest) {
        if(smallest!=Integer.MAX_VALUE && low-smallest>=target)return null;
        if(high>=target && chosen.size()>=min)return chosen.stream().mapToInt(i->i).toArray();
        if(chosen.size()>=max || at>=optional.size() || chosen.size()+optional.size()-at<min)return null;
        long possible=high;for(int i=at;i<optional.size();i++){int w=weights[optional.get(i)];possible+=Math.max(w&65535,w>>>16);}
        if(possible<target)return null;
        int index=optional.get(at),w=weights[index],m=low(w);chosen.add(index);
        int[] found=atLeast(weights,optional,at+1,chosen,min,max,target,low+m,high+Math.max(w&65535,w>>>16),Math.min(smallest,m));
        chosen.remove(chosen.size()-1);
        return found!=null?found:atLeast(weights,optional,at+1,chosen,min,max,target,low,high,smallest);
    }
}
