package com.couplefinance.mobile;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class XlsxAppender {
    public static class Entry {
        public String date="", time="", owner="", type="", category="", description="", account="", note="";
        public double amount=0;
    }

    private static class Part {
        String name; byte[] data;
        Part(String n, byte[] d){name=n;data=d;}
    }

    public static void append(ContentResolver cr, Uri uri, Entry e) throws Exception {
        byte[] original;
        try(InputStream in=cr.openInputStream(uri)){
            if(in==null) throw new Exception("Impossible d'ouvrir le fichier Excel.");
            original=readAll(in);
        }
        List<Part> parts=unzip(original);
        Part sheet=findWritableSheet(parts);
        if(sheet==null) throw new Exception("Aucune feuille Excel utilisable.");
        String xml=new String(sheet.data,StandardCharsets.UTF_8);
        xml=appendRow(xml,e);
        sheet.data=xml.getBytes(StandardCharsets.UTF_8);
        byte[] updated=zip(parts);
        try(OutputStream out=cr.openOutputStream(uri,"rwt")){
            if(out==null) throw new Exception("Le stockage cloud refuse l'écriture.");
            out.write(updated);out.flush();
        }
    }

    private static Part findWritableSheet(List<Part> parts){
        Part first=null;
        for(Part p:parts){
            if(p.name.startsWith("xl/worksheets/sheet")&&p.name.endsWith(".xml")){
                if(first==null)first=p;
                String s=new String(p.data,StandardCharsets.UTF_8);
                String l=s.toLowerCase();
                if(l.contains("date")&&l.contains("montant")) return p;
            }
        }
        return first;
    }

    private static String appendRow(String xml, Entry e) throws Exception {
        int sd=xml.indexOf("<sheetData");
        if(sd<0) throw new Exception("Structure Excel non reconnue : sheetData absent.");
        int open=xml.indexOf('>',sd);
        int close=xml.indexOf("</sheetData>",open);
        if(open<0||close<0) throw new Exception("Structure Excel non reconnue : feuille invalide.");

        String body=xml.substring(open+1,close);
        int max=0, pos=0;
        while((pos=body.indexOf("<row",pos))>=0){
            int end=body.indexOf('>',pos); if(end<0)break;
            String tag=body.substring(pos,end+1);
            int r=attrInt(tag,"r");
            if(r>max)max=r;
            pos=end+1;
        }
        int row=Math.max(2,max+1);
        String[] vals={nz(e.date),nz(e.time),nz(e.owner),nz(e.type),num(e.amount),nz(e.category),nz(e.description),nz(e.account),nz(e.note)};
        StringBuilder r=new StringBuilder("<row r=\"").append(row).append("\">");
        for(int i=0;i<vals.length;i++){
            String ref=col(i+1)+row;
            if(i==4) r.append("<c r=\"").append(ref).append("\" t=\"n\"><v>").append(vals[i]).append("</v></c>");
            else r.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(vals[i])).append("</t></is></c>");
        }
        r.append("</row>");
        return xml.substring(0,close)+r+xml.substring(close);
    }

    private static int attrInt(String tag,String a){
        String q=a+"=\"";int p=tag.indexOf(q);if(p<0)return 0;p+=q.length();int e=tag.indexOf('\"',p);
        if(e<0)return 0;try{return Integer.parseInt(tag.substring(p,e));}catch(Exception x){return 0;}
    }
    private static String col(int n){StringBuilder s=new StringBuilder();while(n>0){n--;s.insert(0,(char)('A'+n%26));n/=26;}return s.toString();}
    private static String num(double n){return Double.toString(n);}
    private static String nz(String s){return s==null?"":s;}
    private static String esc(String s){return nz(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}

    private static List<Part> unzip(byte[] b)throws Exception{
        List<Part> out=new ArrayList<>();
        try(ZipInputStream z=new ZipInputStream(new java.io.ByteArrayInputStream(b))){
            ZipEntry e;byte[] buf=new byte[8192];
            while((e=z.getNextEntry())!=null){
                if(e.isDirectory()){z.closeEntry();continue;}
                ByteArrayOutputStream x=new ByteArrayOutputStream();int n;
                while((n=z.read(buf))>0)x.write(buf,0,n);
                out.add(new Part(e.getName(),x.toByteArray()));z.closeEntry();
            }
        }
        if(out.isEmpty())throw new Exception("Le fichier sélectionné n'est pas un classeur XLSX valide.");
        return out;
    }
    private static byte[] zip(List<Part> parts)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(out)){
            for(Part p:parts){ZipEntry e=new ZipEntry(p.name);z.putNextEntry(e);z.write(p.data);z.closeEntry();}
        }
        return out.toByteArray();
    }
    private static byte[] readAll(InputStream in)throws Exception{
        ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toByteArray();
    }
}
