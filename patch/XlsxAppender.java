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
        List<String> shared=sharedStrings(parts);
        Part sheet=findWritableSheet(parts,shared);
        if(sheet==null) throw new Exception("Feuille de saisie introuvable (en-têtes Date/Montant requis).");
        String xml=new String(sheet.data,StandardCharsets.UTF_8);
        int before=maxRow(xml);
        xml=appendRow(xml,e);
        sheet.data=xml.getBytes(StandardCharsets.UTF_8);
        byte[] updated=zip(parts);
        try(OutputStream out=cr.openOutputStream(uri,"rwt")){
            if(out==null) throw new Exception("Le stockage cloud refuse l'écriture.");
            out.write(updated);out.flush();
        }

        // Never report success until the provider lets us read the newly written row back.
        byte[] check;
        try(InputStream in=cr.openInputStream(uri)){
            if(in==null) throw new Exception("Impossible de relire le fichier Excel après écriture.");
            check=readAll(in);
        }
        List<Part> checkParts=unzip(check);
        Part checkSheet=findWritableSheet(checkParts,sharedStrings(checkParts));
        if(checkSheet==null) throw new Exception("Vérification impossible : feuille de saisie introuvable après écriture.");
        String checkXml=new String(checkSheet.data,StandardCharsets.UTF_8);
        if(maxRow(checkXml)<=before || !rowContainsEntry(checkXml,e)){
            throw new Exception("Écriture non confirmée par le stockage cloud. La saisie n'a pas été validée.");
        }
    }

    private static Part findWritableSheet(List<Part> parts,List<String> shared){
        Part fallback=null;
        for(Part p:parts){
            if(!p.name.startsWith("xl/worksheets/sheet")||!p.name.endsWith(".xml"))continue;
            if(fallback==null)fallback=p;
            String xml=new String(p.data,StandardCharsets.UTF_8);
            String headers=decodedFirstRows(xml,shared,4).toLowerCase();
            boolean date=headers.contains("date");
            boolean amount=headers.contains("montant")||headers.contains("amount");
            boolean type=headers.contains("type")||headers.contains("catégorie")||headers.contains("categorie");
            if(date&&amount&&type)return p;
        }
        // Do not silently write into an arbitrary sheet when the workbook structure is unknown.
        return null;
    }

    private static List<String> sharedStrings(List<Part> parts){
        List<String> out=new ArrayList<>();
        Part ss=null;for(Part p:parts)if("xl/sharedStrings.xml".equals(p.name)){ss=p;break;}
        if(ss==null)return out;
        String x=new String(ss.data,StandardCharsets.UTF_8);int pos=0;
        while((pos=x.indexOf("<si",pos))>=0){int st=x.indexOf('>',pos),en=x.indexOf("</si>",st);if(st<0||en<0)break;String si=x.substring(st+1,en);StringBuilder val=new StringBuilder();int q=0;
            while((q=si.indexOf("<t",q))>=0){int ts=si.indexOf('>',q),te=si.indexOf("</t>",ts);if(ts<0||te<0)break;val.append(unesc(si.substring(ts+1,te)));q=te+4;}
            out.add(val.toString());pos=en+5;
        }
        return out;
    }

    private static String decodedFirstRows(String xml,List<String> shared,int rows){
        StringBuilder out=new StringBuilder();int pos=0,count=0;
        while(count<rows&&(pos=xml.indexOf("<row",pos))>=0){int end=xml.indexOf("</row>",pos);if(end<0)break;String row=xml.substring(pos,end+6);int cpos=0;
            while((cpos=row.indexOf("<c",cpos))>=0){int ce=row.indexOf("</c>",cpos);if(ce<0)break;String cell=row.substring(cpos,ce+4);String tag=cell.substring(0,Math.min(cell.length(),cell.indexOf('>')+1));String v=between(cell,"<v>","</v>");String txt="";
                if(tag.contains("t=\"s\"")&&v!=null){try{int ix=Integer.parseInt(v.trim());if(ix>=0&&ix<shared.size())txt=shared.get(ix);}catch(Exception ignored){}}
                else if(tag.contains("inlineStr")){String t=between(cell,"<t>","</t>");if(t==null){int tp=cell.indexOf("<t ");if(tp>=0){int ts=cell.indexOf('>',tp),te=cell.indexOf("</t>",ts);if(ts>=0&&te>=0)t=cell.substring(ts+1,te);}}if(t!=null)txt=unesc(t);}
                else if(v!=null)txt=v;
                if(!txt.isEmpty())out.append(' ').append(txt);cpos=ce+4;
            }count++;pos=end+6;
        }return out.toString();
    }

    private static String between(String s,String a,String b){int p=s.indexOf(a);if(p<0)return null;p+=a.length();int e=s.indexOf(b,p);return e<0?null:s.substring(p,e);}
    private static String unesc(String s){return s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&amp;","&");}
    private static int maxRow(String xml){int max=0,pos=0;while((pos=xml.indexOf("<row",pos))>=0){int end=xml.indexOf('>',pos);if(end<0)break;int r=attrInt(xml.substring(pos,end+1),"r");if(r>max)max=r;pos=end+1;}return max;}
    private static boolean rowContainsEntry(String xml,Entry e){
        String tail=xml.substring(Math.max(0,xml.length()-12000));
        return tail.contains(esc(nz(e.date)))&&tail.contains(esc(nz(e.description)))&&tail.contains("<v>"+num(e.amount)+"</v>");
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
