import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Applies a hash-pinned release delta using bounded memory; never edits the source archive. */
public final class ApplyReleaseDelta {
    static String sha(Path path) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)){byte[] b=new byte[1024*1024];for(int n;(n=in.read(b))>=0;)digest.update(b,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),delta=Path.of(args[1]),target=Path.of(args[2]);
        if(source.toRealPath().equals(target.toAbsolutePath())||Files.exists(target))throw new IOException("Target already exists or equals source");
        Path temp=target.resolveSibling(target.getFileName()+".partial");
        try(var patch=new DataInputStream(new BufferedInputStream(Files.newInputStream(delta)))) {
            if(patch.readInt()!=0x4D445044)throw new IOException("Invalid delta");
            String before=patch.readUTF(),after=patch.readUTF();
            if(!sha(source).equals(before))throw new IOException("Source hash mismatch");
            try(var input=new RandomAccessFile(source.toFile(),"r");var output=new BufferedOutputStream(Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW))) {
                byte[] buffer=new byte[1024*1024];
                for(int op;(op=patch.readUnsignedByte())!=0;) {
                    if(op!=1&&op!=2)throw new IOException("Invalid operation");
                    if(op==1)input.seek(patch.readLong());
                    long remaining=patch.readLong();
                    if(remaining<0)throw new IOException("Invalid length");
                    while(remaining>0){int n=(int)Math.min(remaining,buffer.length);if(op==1)input.readFully(buffer,0,n);else patch.readFully(buffer,0,n);output.write(buffer,0,n);remaining-=n;}
                }
                if(patch.read()!=-1)throw new IOException("Trailing delta data");
            }
            if(!sha(temp).equals(after))throw new IOException("Output hash mismatch");
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            System.out.println("PASS exact release reconstructed: "+after+" "+target);
        } catch(Exception e){Files.deleteIfExists(temp);throw e;}
    }
}
