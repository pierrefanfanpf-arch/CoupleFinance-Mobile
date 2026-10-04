package com.couplefinance.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 3001;
    private static final int PICK_WORKBOOK = 3002;
    private static final int PICK_VIEW = 3003;
    private static final String PREFS = "cf_mobile_v2";
    private static final String PREF_TREE = "onedrive_tree_uri";
    private static final String PREF_WORKBOOK = "onedrive_workbook_uri";
    private static final String PREF_VIEW = "onedrive_view_uri";
    private static final String PREF_LINK = "onedrive_folder_link";
    private static final String PREF_PHONE_HISTORY = "phone_movement_history_v1";
    private static final String WORKBOOK_NAME = "CoupleFinance_Mobile.xlsx";
    private static final String VIEW_NAME = "CoupleFinance_Mobile_View.json";

    private final Handler handler = new Handler();
    private Uri treeUri;
    private Uri workbookUri;
    private Uri viewUri;
    private JSONObject snapshot;

    private LinearLayout root;
    private LinearLayout content;
    private TextView connectionStatus;
    private TextView syncStatus;
    private EditText oneDriveLink;
    private String currentSection = "Accueil";
    private String movementFilter = "Téléphone";
    private String accountFilter = "Tous";
    private String debtFilter = "Carte de crédit";
    private String analysisFilter = "Par catégorie";

    private Spinner entryType, owner, category, sourceAccount, destinationAccount, debtAccount;
    private EditText amount, date, time, description, note;
    private LinearLayout destinationBlock, debtBlock;
    private final List<String> debtIds = new ArrayList<>();
    private Button saveButton;

    private final String[] sections = new String[]{
            "Accueil","Comptes","Revenus","Dépenses","Transactions","Dettes","Épargne","Agenda","Saisie"
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (treeUri != null || viewUri != null) {
                try { refreshSnapshot(false); } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 30000);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildShell());
        restoreFolder();
        handler.postDelayed(autoRefresh, 30000);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(autoRefresh);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (treeUri != null || viewUri != null) {
            try { refreshSnapshot(false); } catch (Exception ignored) {}
        }
    }

    private View buildShell() {
        root = vertical();
        root.setBackgroundColor(Color.rgb(248,250,253));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14),dp(12),dp(12),dp(12));
        GradientDrawable hg=new GradientDrawable(); hg.setColor(Color.WHITE); header.setBackground(hg); header.setElevation(dp(3));

        TextView heart=text("♥",36,true); heart.setTextColor(Color.rgb(255,42,82)); heart.setGravity(Gravity.CENTER);
        header.addView(heart,new LinearLayout.LayoutParams(dp(48),dp(54)));

        LinearLayout brand=vertical();
        TextView title=text("Couple Finance",23,true); title.setTextColor(Color.rgb(8,35,70)); brand.addView(title);
        TextView sub=text("Gestion financière • v2.16",11,false); sub.setTextColor(Color.rgb(91,105,120)); brand.addView(sub);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));

        TextView sync=text("↻",27,true); sync.setTextColor(Color.rgb(8,35,70)); sync.setGravity(Gravity.CENTER);
        sync.setOnClickListener(v->refreshSnapshot(true)); header.addView(sync,new LinearLayout.LayoutParams(dp(44),dp(52)));
        TextView gear=text("⚙",28,true); gear.setTextColor(Color.rgb(8,35,70)); gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(v->showSection("Paramètres")); header.addView(gear,new LinearLayout.LayoutParams(dp(48),dp(52)));
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        ScrollView pageScroll=new ScrollView(this); pageScroll.setFillViewport(true);
        content=vertical(); content.setPadding(dp(12),dp(10),dp(12),dp(18));
        pageScroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        root.addView(pageScroll,new LinearLayout.LayoutParams(-1,0,1));
        View bottomNav=buildBottomNav();
        root.addView(bottomNav,new LinearLayout.LayoutParams(-1,dp(72)));
        bottomNav.setOnApplyWindowInsetsListener((v,insets)->{
            int bottom;
            if(android.os.Build.VERSION.SDK_INT>=30) bottom=insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            else bottom=insets.getSystemWindowInsetBottom();
            v.setPadding(dp(4),dp(4),dp(4),dp(4)+bottom);
            android.view.ViewGroup.LayoutParams lp=v.getLayoutParams();
            lp.height=dp(72)+bottom;
            v.setLayoutParams(lp);
            return insets;
        });
        bottomNav.requestApplyInsets();

        connectionStatus=text("Aucun stockage cloud connecté.",12,false);
        syncStatus=text("",11,false);
        oneDriveLink=input("https://1drv.ms/... ou https://onedrive.live.com/...");
        oneDriveLink.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LINK, ""));
        showSection("Accueil");
        return root;
    }

    private View buildBottomNav(){
        LinearLayout nav=horizontal(); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(4),dp(4),dp(4),dp(4));
        nav.setBackgroundColor(Color.WHITE); nav.setElevation(dp(10));
        nav.addView(bottomButton("⌂","Accueil","Accueil",false),weight());
        nav.addView(bottomButton("⇄","Mouvements","Mouvements",false),weight());
        nav.addView(bottomButton("＋","Saisie","Saisie",true),weight());
        nav.addView(bottomButton("▣","Comptes","Comptes",false),weight());
        nav.addView(bottomButton("▦","Plus","Plus",false),weight());
        return nav;
    }

    private Button bottomButton(String icon,String label,String section,boolean center){
        Button b=button(icon+"\n"+label); b.setGravity(Gravity.CENTER); b.setTextSize(center?12:11);
        b.setTextColor(center?Color.WHITE:Color.rgb(66,76,88)); b.setPadding(dp(2),dp(2),dp(2),dp(2));
        GradientDrawable g=new GradientDrawable(); g.setColor(center?Color.rgb(0,166,96):Color.WHITE); g.setCornerRadius(dp(center?32:4)); b.setBackground(g);
        if(center)b.setElevation(dp(7)); b.setOnClickListener(v->showSection(section)); return b;
    }

    private void saveOneDriveLink() {
        String link = oneDriveLink == null ? "" : oneDriveLink.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LINK, link).apply();
        toast(link.isEmpty() ? "Lien OneDrive effacé." : "Lien OneDrive mémorisé.");
    }

    private void openOneDriveLink() {
        String link = oneDriveLink == null ? "" : oneDriveLink.getText().toString().trim();
        if (link.isEmpty()) { toast("Entre d'abord le lien du dossier OneDrive."); return; }
        if (!(link.startsWith("https://") || link.startsWith("http://"))) { link = "https://" + link; oneDriveLink.setText(link); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LINK, link).apply();
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
        catch (Exception e) { toast("Impossible d'ouvrir ce lien OneDrive : " + e.getMessage()); }
    }

    private void chooseWorkbookFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_WORKBOOK);
    }

    private void chooseViewFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_VIEW);
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();

        if (req == PICK_FOLDER) {
            treeUri = uri; ed.putString(PREF_TREE, uri.toString()).apply();
            try { locateFiles(); ensureWorkbook(); refreshSnapshot(true); }
            catch (Exception e) { setConnection(false, "Erreur stockage cloud : " + e.getMessage()); }
            return;
        }
        if (req == PICK_WORKBOOK) {
            workbookUri = uri; ed.putString(PREF_WORKBOOK, uri.toString()).apply();
            setConnection(true, "Excel cloud sélectionné."); toast("Fichier Excel cloud mémorisé."); return;
        }
        if (req == PICK_VIEW) {
            viewUri = uri; ed.putString(PREF_VIEW, uri.toString()).apply();
            setConnection(true, "Vue PC cloud sélectionnée."); refreshSnapshot(true);
        }
    }

    private void restoreFolder() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String t = sp.getString(PREF_TREE, ""), w = sp.getString(PREF_WORKBOOK, ""), v = sp.getString(PREF_VIEW, ""), link = sp.getString(PREF_LINK, "");
        if (oneDriveLink != null) oneDriveLink.setText(link);
        if (!t.isEmpty()) treeUri = Uri.parse(t);
        if (!w.isEmpty()) workbookUri = Uri.parse(w);
        if (!v.isEmpty()) viewUri = Uri.parse(v);
        try {
            if (treeUri != null) locateFiles();
            if (treeUri != null && workbookUri == null) ensureWorkbook();
            if (viewUri != null || treeUri != null) refreshSnapshot(false);
            else if (workbookUri != null) setConnection(true, "Excel cloud mémorisé. Choisis maintenant la vue PC JSON.");
        } catch (Exception e) { setConnection(false, "Stockage cloud mémorisé inaccessible : " + e.getMessage()); }
    }

    private Uri treeDocumentUri() {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
    }

    private void locateFiles() throws Exception {
        if (treeUri == null) return;
        Uri wb = findChild(WORKBOOK_NAME), vw = findChild(VIEW_NAME);
        if (wb != null) workbookUri = wb;
        if (vw != null) viewUri = vw;
        setConnection(true, "Dossier cloud connecté.");
    }

    private Uri findChild(String name) throws Exception {
        if (treeUri == null) return null;
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor c = getContentResolver().query(children, projection, null, null, null)) {
            if (c != null) {
                int idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (c.moveToNext()) {
                    String display = c.getString(nameIndex);
                    if (name.equalsIgnoreCase(display)) {
                        String docId = c.getString(idIndex);
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    }
                }
            }
        }
        return null;
    }

    private void ensureWorkbook() throws Exception {
        if (workbookUri != null) return;
        Uri created = DocumentsContract.createDocument(
                getContentResolver(),
                treeDocumentUri(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                WORKBOOK_NAME);
        if (created == null) throw new Exception("Impossible de créer " + WORKBOOK_NAME);
        try (InputStream in = getAssets().open(WORKBOOK_NAME);
             OutputStream out = getContentResolver().openOutputStream(created, "wt")) {
            if (out == null) throw new Exception("OneDrive refuse l'écriture du fichier Excel.");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
        workbookUri = created;
    }

    private void refreshSnapshot(boolean userMessage) {
        if (treeUri == null && viewUri == null) {
            if (userMessage) toast("Choisis le dossier OneDrive ou le fichier CoupleFinance_Mobile_View.json.");
            return;
        }
        try {
            if (treeUri != null) {
                locateFiles();
                if (workbookUri == null) ensureWorkbook();
                Uri detectedView = findChild(VIEW_NAME);
                if (detectedView != null) viewUri = detectedView;
            }
            if (viewUri == null) {
                snapshot = null;
                if(syncStatus!=null)syncStatus.setText("La vue lecture seule n'est pas encore disponible. Ouvrez Couple Finance sur le PC puis sauvegardez/actualisez la vue téléphone.");
                if (userMessage) toast("Fichier de vue non trouvé dans OneDrive.");
                showSection(currentSection);
                return;
            }
            String raw = readText(viewUri);
            snapshot = new JSONObject(raw);
            String gen = snapshot.optString("generatedAt", "—");
            String period = snapshot.optString("period", "—");
            if(syncStatus!=null)syncStatus.setText("Vue PC : " + gen + "   •   " + period);
            setConnection(true, "OneDrive connecté • Excel de saisie + vue PC détectés");
            showSection(currentSection);
            if (userMessage) toast("Vue Couple Finance actualisée.");
        } catch (Exception e) {
            setConnection(false, "Actualisation impossible : " + e.getMessage());
            if (userMessage) toast(e.getMessage());
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("Impossible d'ouvrir la vue Couple Finance.");
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\uFEFF", "");
        }
    }

    private void setConnection(boolean ok, String msg) {
        if(connectionStatus!=null){connectionStatus.setText((ok ? "✓ " : "⚠ ") + msg);connectionStatus.setTextColor(ok ? Color.rgb(24,137,91) : Color.rgb(190,55,55));}
    }

    private void showSection(String section) {
        currentSection=section;if(content==null)return;content.removeAllViews();
        if("Accueil".equals(section)){renderHome();return;}
        if("Paramètres".equals(section)){renderSettings();return;}
        if("Plus".equals(section)){renderMore();return;}
        if("Historique téléphone".equals(section)){renderPhoneHistory(false);return;}
        if("Mouvements".equals(section)){renderMovements();return;}
        TextView h=text(section,22,true);h.setTextColor(Color.rgb(7,51,94));h.setPadding(dp(2),dp(8),0,dp(4));content.addView(h);
        if("Saisie".equals(section)){buildEntryForm();return;}
        TextView ro=text("Dépenses".equals(section)?"Lecture seule, sauf l’action Reporter sur une échéance.":"Lecture seule — les données principales proviennent de l'application PC.",11,false);
        ro.setTextColor(Color.GRAY);content.addView(ro);spacer(content,8);
        if(snapshot==null){content.addView(infoCard("Aucune donnée","Connecte le stockage cloud dans Plus → Paramètres, puis actualise la vue PC."));return;}
        switch(section){
            case "Comptes":renderAccounts();break;case "Revenus":renderIncome();break;case "Dépenses":renderExpenses();break;
            case "Transactions":renderTransactions();break;case "Dettes":renderDebtsGoals();break;case "Épargne":renderSavings();break;
            case "Agenda":renderAgenda();break;case "Budget":renderBudgetMobile();break;case "Objectifs":renderGoalsMobile();break;
            case "Projections":renderProjectionMobile();break;case "Rapports":renderReportsMobile();break;default:renderHome();
        }
    }

    private void renderHome(){
        String per=snapshot==null?"Octobre 2026":snapshot.optString("period","Octobre 2026");
        LinearLayout period=horizontal();period.setGravity(Gravity.CENTER_VERTICAL);period.setPadding(dp(2),dp(2),dp(2),dp(6));
        TextView perText=text(per+" ▾",12,true);perText.setTextColor(Color.rgb(8,35,70));period.addView(perText,new LinearLayout.LayoutParams(0,dp(38),1));
        TextView state=text(snapshot!=null?"✓ Synchronisé":"○ Hors ligne",10,true);state.setGravity(Gravity.CENTER);state.setTextColor(snapshot!=null?Color.rgb(0,145,84):Color.GRAY);
        period.addView(state,new LinearLayout.LayoutParams(dp(100),dp(38)));content.addView(period);

        JSONObject sm=snapshot==null?null:snapshot.optJSONObject("summary");if(sm==null)sm=new JSONObject();
        double inc=sm.optDouble("incomeReceived",0),exp=sm.optDouble("expenseActual",0),planned=sm.optDouble("expensePlanned",0),solde=inc-exp;
        LinearLayout situation=softCard(Color.WHITE,Color.rgb(225,232,240));
        TextView sh=text("Situation du mois",15,true);sh.setTextColor(Color.rgb(8,35,70));situation.addView(sh);
        LinearLayout kpis=horizontal();
        kpis.addView(compactMetric("Revenus",money(inc),Color.rgb(8,35,70)),weight());
        kpis.addView(compactMetric("Dépenses",money(exp),Color.rgb(8,35,70)),weight());
        kpis.addView(compactMetric("Solde prévu",money(solde),solde>=0?Color.rgb(0,150,83):Color.rgb(220,45,60)),weight());
        situation.addView(kpis);
        double used=planned>0?Math.min(100,exp/planned*100):0;situation.addView(progressLine(used,Color.rgb(0,166,96)));
        TextView util=text(Math.round(used)+"% du budget utilisé",9,true);util.setGravity(Gravity.RIGHT);util.setTextColor(Color.rgb(70,82,96));situation.addView(util);content.addView(situation);

        LinearLayout quick=horizontal();
        quick.addView(coloredAction("＋","Dépense",Color.rgb(20,132,235),0),weight());
        quick.addView(coloredAction("＋","Revenu",Color.rgb(36,181,83),1),weight());
        quick.addView(coloredAction("▣","Rembours.",Color.rgb(255,143,24),3),weight());
        quick.addView(coloredAction("⇄","Transfert",Color.rgb(126,67,210),4),weight());
        content.addView(quick);

        renderPhoneHistory(true);
        renderUpcomingHome();
    }

    private View compactMetric(String label,String value,int color){
        LinearLayout c=vertical();c.setPadding(dp(3),dp(7),dp(3),dp(7));
        TextView l=text(label,9,false);l.setTextColor(Color.rgb(80,93,108));c.addView(l);
        TextView v=text(value,16,true);v.setTextColor(color);c.addView(v);return c;
    }

    private View coloredAction(String icon,String label,int color,int typeIndex){
        LinearLayout c=vertical();c.setGravity(Gravity.CENTER);c.setPadding(dp(3),dp(10),dp(3),dp(10));
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(12));c.setBackground(g);c.setElevation(dp(2));
        TextView i=text(icon,19,true);i.setTextColor(Color.WHITE);i.setGravity(Gravity.CENTER);c.addView(i);
        TextView t=text(label,9,true);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);c.addView(t);
        c.setOnClickListener(v->{showSection("Saisie");selectEntryType(typeIndex);});return c;
    }

    private View progressLine(double pct,int color){
        pct=Math.max(0,Math.min(100,pct));LinearLayout track=horizontal();track.setWeightSum(100f);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(232,237,242));bg.setCornerRadius(dp(6));track.setBackground(bg);
        View fill=new View(this);GradientDrawable fg=new GradientDrawable();fg.setColor(color);fg.setCornerRadius(dp(6));fill.setBackground(fg);
        track.addView(fill,new LinearLayout.LayoutParams(0,dp(8),(float)Math.max(.1,pct)));
        View rest=new View(this);track.addView(rest,new LinearLayout.LayoutParams(0,dp(8),(float)Math.max(.1,100-pct)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(8));lp.setMargins(0,dp(6),0,dp(4));track.setLayoutParams(lp);return track;
    }

    private int arrayLen(String key){JSONArray a=snapshot==null?null:snapshot.optJSONArray(key);return a==null?0:a.length();}

    private View homeKpi(String title,String value,String sub,int bg,int fg){
        LinearLayout c=softCard(bg,bg);c.setPadding(dp(8),dp(12),dp(7),dp(12));TextView t=text(title,11,true);t.setTextColor(fg);c.addView(t);
        TextView v=text(value,18,true);v.setTextColor(fg);v.setPadding(0,dp(8),0,dp(3));c.addView(v);TextView x=text(sub,9,false);x.setTextColor(fg);c.addView(x);return c;
    }
    private View summaryTile(String icon,String title,String value,String sub,String section){
        LinearLayout c=softCard(Color.WHITE,Color.rgb(226,232,240));c.setPadding(dp(5),dp(8),dp(5),dp(8));c.setGravity(Gravity.CENTER);
        TextView i=text(icon,18,true);i.setTextColor(Color.rgb(9,59,106));i.setGravity(Gravity.CENTER);c.addView(i);
        TextView t=text(title,9,true);t.setTextColor(Color.rgb(9,48,91));t.setGravity(Gravity.CENTER);c.addView(t);
        TextView v=text(value,12,true);v.setTextColor(Color.rgb(5,35,74));v.setGravity(Gravity.CENTER);c.addView(v);
        TextView x=text(sub,8,false);x.setTextColor(Color.GRAY);x.setGravity(Gravity.CENTER);c.addView(x);c.setOnClickListener(vw->showSection(section));return c;
    }
    private View menuTile(String icon,String label,String section,boolean active){
        LinearLayout c=softCard(active?Color.rgb(232,244,255):Color.WHITE,active?Color.rgb(135,191,250):Color.rgb(226,232,240));c.setPadding(dp(2),dp(9),dp(2),dp(9));c.setGravity(Gravity.CENTER);
        TextView i=text(icon,19,true);i.setGravity(Gravity.CENTER);i.setTextColor(active?Color.rgb(18,91,195):Color.rgb(8,59,106));c.addView(i);
        TextView t=text(label,8,active);t.setGravity(Gravity.CENTER);t.setTextColor(active?Color.rgb(18,91,195):Color.rgb(8,35,73));c.addView(t);c.setOnClickListener(v->showSection(section));return c;
    }

    private void renderUpcomingHome(){
        LinearLayout card=softCard(Color.WHITE,Color.rgb(226,232,240));LinearLayout hr=horizontal();TextView h=text("Prochaines échéances",18,true);h.setTextColor(Color.rgb(7,51,94));hr.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        Button all=smallPill("Voir tout ›");all.setOnClickListener(v->showSection("Dépenses"));hr.addView(all,new LinearLayout.LayoutParams(dp(92),dp(40)));card.addView(hr);
        JSONArray a=snapshot==null?null:snapshot.optJSONArray("recurringView");if(a==null||a.length()==0)a=snapshot==null?null:snapshot.optJSONArray("upcoming");
        if(a==null||a.length()==0){TextView e=text("Aucune échéance disponible.",11,false);e.setTextColor(Color.GRAY);e.setPadding(0,dp(10),0,dp(6));card.addView(e);}
        else for(int i=0;i<Math.min(4,a.length());i++){final JSONObject x=a.optJSONObject(i);if(x==null)continue;LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(7),0,dp(7));
            String ds=x.optString("dueDate",x.optString("date","—"));TextView day=text(shortDate(ds),10,true);day.setGravity(Gravity.CENTER);day.setTextColor(Color.rgb(170,35,45));GradientDrawable dg=new GradientDrawable();dg.setColor(Color.rgb(255,239,242));dg.setCornerRadius(dp(9));day.setBackground(dg);row.addView(day,new LinearLayout.LayoutParams(dp(54),dp(50)));
            LinearLayout info=vertical();TextView n=text(x.optString("name",x.optString("desc","Échéance")),12,true);n.setTextColor(Color.rgb(7,45,84));info.addView(n);TextView sub=text(x.optString("owner",x.optString("cat","Commun")),9,false);sub.setTextColor(Color.GRAY);info.addView(sub);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            TextView amt=text(money(x.optDouble("amount",0)),11,true);amt.setTextColor(Color.rgb(7,45,84));amt.setGravity(Gravity.RIGHT);row.addView(amt,new LinearLayout.LayoutParams(dp(78),-2));
            if(x.has("id")||x.has("period")){Button rep=smallPill("▣ Reporter");rep.setOnClickListener(v->chooseDeferralDate(x));row.addView(rep,new LinearLayout.LayoutParams(dp(88),dp(42)));}card.addView(row);
        }content.addView(card);
    }
    private String shortDate(String s){try{Date d=new SimpleDateFormat("yyyy-MM-dd",Locale.CANADA_FRENCH).parse(s);return new SimpleDateFormat("dd\nMMM",Locale.CANADA_FRENCH).format(d).replace(".","");}catch(Exception e){return s.length()>5?s.substring(Math.max(0,s.length()-5)):s;}}

    private void renderExpenseDistributionHome(double total){
        LinearLayout card=softCard(Color.WHITE,Color.rgb(226,232,240));LinearLayout hr=horizontal();TextView h=text("Répartition des dépenses",18,true);h.setTextColor(Color.rgb(7,51,94));hr.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        Button all=smallPill("Voir le détail");all.setOnClickListener(v->showSection("Dépenses"));hr.addView(all,new LinearLayout.LayoutParams(dp(104),dp(40)));card.addView(hr);
        JSONArray b=snapshot==null?null:snapshot.optJSONArray("budgets");List<Double> vals=new ArrayList<>();List<String> labels=new ArrayList<>();double sum=0;
        if(b!=null)for(int i=0;i<b.length()&&i<6;i++){JSONObject x=b.optJSONObject(i);if(x==null)continue;double v=Math.max(0,x.optDouble("spent",0));if(v<=0)continue;vals.add(v);labels.add(x.optString("cat","Autres"));sum+=v;}
        if(vals.isEmpty()){vals.add(Math.max(1,total));labels.add("Dépenses");sum=Math.max(1,total);}
        LinearLayout body=horizontal();ExpenseDonutView donut=new ExpenseDonutView(vals,sum,total);body.addView(donut,new LinearLayout.LayoutParams(dp(145),dp(145)));
        LinearLayout legend=vertical();int[] cs={Color.rgb(51,143,238),Color.rgb(246,53,78),Color.rgb(91,174,100),Color.rgb(255,177,47),Color.rgb(132,79,221),Color.rgb(70,194,120)};
        for(int i=0;i<vals.size();i++){double pct=sum>0?vals.get(i)/sum*100:0;TextView l=text("● "+labels.get(i)+"  "+Math.round(pct)+"%  "+money(vals.get(i)),9,false);l.setTextColor(cs[i%cs.length]);legend.addView(l);}
        body.addView(legend,new LinearLayout.LayoutParams(0,-2,1));card.addView(body);content.addView(card);
    }

    private void renderSettings(){
        TextView h=text("Paramètres",22,true);h.setTextColor(Color.rgb(7,51,94));content.addView(h);TextView sh=text("Stockage cloud",16,true);sh.setTextColor(Color.rgb(7,51,94));sh.setPadding(0,dp(8),0,dp(4));content.addView(sh);
        LinearLayout c=softCard(Color.WHITE,Color.rgb(220,229,238));oneDriveLink=input("https://1drv.ms/... ou https://onedrive.live.com/...");oneDriveLink.setText(getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_LINK,""));addLabeled(c,"Lien du dossier OneDrive (optionnel)",oneDriveLink);
        LinearLayout lr=horizontal();Button save=smallPill("Mémoriser le lien");save.setOnClickListener(v->saveOneDriveLink());lr.addView(save,weight());Button open=smallPill("Ouvrir OneDrive");open.setOnClickListener(v->openOneDriveLink());lr.addView(open,weight());c.addView(lr);
        Button folder=primaryButton("Choisir dossier OneDrive / Google Drive");folder.setOnClickListener(v->chooseFolder());c.addView(folder,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout fr=horizontal();Button excel=smallPill("Choisir Excel");excel.setOnClickListener(v->chooseWorkbookFile());fr.addView(excel,weight());Button json=smallPill("Choisir vue PC JSON");json.setOnClickListener(v->chooseViewFile());fr.addView(json,weight());c.addView(fr);
        Button refresh=primaryButton("↻ Actualiser maintenant");refresh.setOnClickListener(v->refreshSnapshot(true));c.addView(refresh,new LinearLayout.LayoutParams(-1,-2));
        connectionStatus=text((treeUri!=null||workbookUri!=null||viewUri!=null)?"✓ Stockage cloud mémorisé":"Aucun stockage cloud connecté.",12,false);connectionStatus.setTextColor((treeUri!=null||workbookUri!=null||viewUri!=null)?Color.rgb(24,137,91):Color.GRAY);connectionStatus.setPadding(0,dp(8),0,dp(2));c.addView(connectionStatus);
        syncStatus=text(snapshot==null?"Vue PC non chargée.":"Vue PC : "+snapshot.optString("generatedAt","—")+" • "+snapshot.optString("period","—"),11,false);syncStatus.setTextColor(Color.GRAY);c.addView(syncStatus);content.addView(c);
    }
    private View actionTile(String icon,String title,String sub,String section){
        LinearLayout c=softCard(Color.WHITE,Color.rgb(226,232,240));c.setGravity(Gravity.CENTER);c.setPadding(dp(5),dp(11),dp(5),dp(11));
        TextView i=text(icon,22,true);i.setGravity(Gravity.CENTER);i.setTextColor(Color.rgb(18,102,210));c.addView(i);
        TextView t=text(title,10,true);t.setGravity(Gravity.CENTER);t.setTextColor(Color.rgb(7,51,94));c.addView(t);
        TextView x=text(sub,8,false);x.setGravity(Gravity.CENTER);x.setTextColor(Color.GRAY);c.addView(x);
        c.setOnClickListener(v->{if("SYNC".equals(section))refreshSnapshot(true);else showSection(section);});return c;
    }

    private void renderMovements(){
        screenTitle("Mouvements");
        LinearLayout filters=horizontal();String[] names={"Tous","Téléphone","PC","Dépenses","Revenus"};
        for(String name:names)filters.addView(movementFilterChip(name),weight());content.addView(filters);
        LinearLayout note=softCard(Color.rgb(242,248,253),Color.rgb(218,230,240));
        TextView n=text("ⓘ Filtre actif : "+movementFilter+" • historique téléphone enregistré après écriture Excel réussie.",10,false);n.setTextColor(Color.rgb(73,91,108));note.addView(n);content.addView(note);
        if(!"PC".equals(movementFilter))renderPhoneHistoryFiltered(movementFilter);
        if(!"Téléphone".equals(movementFilter)&&snapshot!=null){
            content.addView(sectionLabel("Mouvements synchronisés du PC"));JSONArray tx=snapshot.optJSONArray("transactions");int shown=0;
            if(tx!=null)for(int i=tx.length()-1;i>=0&&shown<30;i--){JSONObject x=tx.optJSONObject(i);if(x==null)continue;double amt=x.optDouble("amount",0);String typ=x.optString("operation",x.optString("type",""));boolean income=amt>0||"Revenu".equalsIgnoreCase(typ);
                if("Dépenses".equals(movementFilter)&&income)continue;if("Revenus".equals(movementFilter)&&!income)continue;
                content.addView(movementRow(x.optString("date",""),x.optString("desc","Transaction"),x.optString("cat",""),amt,"PC"));shown++;}
        }
    }
    private View movementFilterChip(String label){View t=filterChip(label,label.equals(movementFilter));t.setOnClickListener(v->{movementFilter=label;showSection("Mouvements");});return t;}
    private void renderPhoneHistoryFiltered(String filter){
        JSONArray a=loadPhoneHistory();LinearLayout box=softCard(Color.WHITE,Color.rgb(226,232,240));TextView h=text("Historique téléphone",15,true);h.setTextColor(Color.rgb(7,51,94));box.addView(h);int shown=0;
        for(int i=0;i<a.length()&&shown<250;i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String typ=x.optString("type","");boolean income="Revenu".equals(typ);if("Dépenses".equals(filter)&&income)continue;if("Revenus".equals(filter)&&!income)continue;
            LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));TextView icon=text(phoneMovementIcon(typ),20,true);icon.setGravity(Gravity.CENTER);icon.setTextColor(income?Color.rgb(0,150,83):Color.rgb(18,102,210));row.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(46)));
            LinearLayout info=vertical();TextView tt=text(x.optString("description",typ),12,true);tt.setTextColor(Color.rgb(7,45,84));info.addView(tt);TextView meta=text(x.optString("date","")+" "+x.optString("time","")+" • "+x.optString("owner","Commun")+" • "+x.optString("category",""),9,false);meta.setTextColor(Color.GRAY);info.addView(meta);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout right=vertical();double amt=x.optDouble("amount",0);TextView val=text((income?"+ ":"− ")+money(Math.abs(amt)),11,true);val.setGravity(Gravity.RIGHT);val.setTextColor(income?Color.rgb(0,150,83):Color.rgb(215,48,62));right.addView(val);TextView st=text("✓ "+x.optString("status","Enregistré"),8,true);st.setGravity(Gravity.RIGHT);st.setTextColor(Color.rgb(0,150,83));right.addView(st);row.addView(right,new LinearLayout.LayoutParams(dp(112),-2));box.addView(row);shown++;}
        if(shown==0){TextView e=text("Aucun mouvement pour ce filtre.",11,false);e.setTextColor(Color.GRAY);e.setPadding(0,dp(10),0,dp(8));box.addView(e);}content.addView(box);
    }

    private View filterChip(String label,boolean active){
        Button t=new Button(this);
        t.setText(label);t.setTextSize(9);t.setAllCaps(false);t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT,active?Typeface.BOLD:Typeface.NORMAL);
        t.setTextColor(active?Color.WHITE:Color.rgb(75,88,102));
        t.setMinHeight(0);t.setMinimumHeight(0);t.setMinWidth(0);t.setMinimumWidth(0);
        t.setPadding(dp(5),0,dp(5),0);t.setClickable(true);t.setFocusable(true);
        GradientDrawable g=new GradientDrawable();g.setColor(active?Color.rgb(0,166,96):Color.WHITE);g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(224,231,238));t.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.setMargins(dp(2),dp(3),dp(2),dp(6));t.setLayoutParams(p);return t;
    }

    private View movementRow(String dateText,String title,String sub,double amount,String status){
        LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(10));g.setStroke(dp(1),Color.rgb(230,235,240));row.setBackground(g);
        TextView ico=text(amount>=0?"＋":"−",16,true);ico.setGravity(Gravity.CENTER);ico.setTextColor(amount>=0?Color.rgb(0,155,86):Color.rgb(225,65,70));row.addView(ico,new LinearLayout.LayoutParams(dp(34),dp(40)));
        LinearLayout mid=vertical();TextView t=text(title,11,true);t.setTextColor(Color.rgb(8,35,70));mid.addView(t);TextView m=text(dateText+" • "+sub,9,false);m.setTextColor(Color.GRAY);mid.addView(m);row.addView(mid,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout right=vertical();TextView v=text((amount>=0?"+ ":"− ")+money(Math.abs(amount)),11,true);v.setGravity(Gravity.RIGHT);v.setTextColor(amount>=0?Color.rgb(0,150,83):Color.rgb(215,48,62));right.addView(v);TextView st=text("✓ "+status,8,false);st.setGravity(Gravity.RIGHT);st.setTextColor(Color.rgb(0,150,83));right.addView(st);row.addView(right,new LinearLayout.LayoutParams(dp(112),-2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(3),0,dp(3));row.setLayoutParams(lp);return row;
    }

    private void renderMore(){
        screenTitle("Plus");
        String[][] items={{"◉","Dettes","Dettes"},{"▣","Épargne et placements","Épargne"},{"▦","Agenda et échéances","Agenda"},{"▥","Budget","Budget"},{"◎","Objectifs","Objectifs"},{"▤","Projections","Projections"},{"▤","Rapports","Rapports"},{"⚙","Paramètres","Paramètres"}};
        for(String[] it:items)content.addView(menuListRow(it[0],it[1],it[2]));
    }

    private View menuListRow(String icon,String label,String section){
        LinearLayout r=horizontal();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(10),dp(10),dp(10),dp(10));
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(10));g.setStroke(dp(1),Color.rgb(230,235,240));r.setBackground(g);
        TextView i=text(icon,16,true);i.setGravity(Gravity.CENTER);i.setTextColor(Color.rgb(0,166,96));r.addView(i,new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView t=text(label,12,true);t.setTextColor(Color.rgb(8,35,70));r.addView(t,new LinearLayout.LayoutParams(0,-2,1));TextView a=text("›",22,false);a.setTextColor(Color.GRAY);r.addView(a);
        r.setOnClickListener(v->showSection(section));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(3),0,dp(3));r.setLayoutParams(lp);return r;
    }

    private void renderBudgetMobile(){
        screenTitle("Budget - "+(snapshot==null?"":snapshot.optString("period","")));
        JSONObject sm=snapshot==null?null:snapshot.optJSONObject("summary");if(sm==null)sm=new JSONObject();
        double budget=sm.optDouble("expensePlanned",0),spent=sm.optDouble("expenseActual",0),gap=budget-spent;
        LinearLayout top=softCard(Color.WHITE,Color.rgb(225,232,240));LinearLayout row=horizontal();
        row.addView(compactMetric("Total budget",money(budget),Color.rgb(8,35,70)),weight());
        row.addView(compactMetric("Dépense réelle",money(spent),Color.rgb(210,45,60)),weight());
        row.addView(compactMetric("Écart",money(gap),gap>=0?Color.rgb(0,150,83):Color.rgb(210,45,60)),weight());top.addView(row);
        top.addView(progressLine(budget>0?spent/budget*100:0,Color.rgb(0,166,96)));content.addView(top);
        JSONArray a=snapshot==null?null:snapshot.optJSONArray("budgets");if(empty(a)){emptyState("Aucun budget.");return;}
        int[] cs={Color.rgb(246,92,90),Color.rgb(0,166,96),Color.rgb(38,143,235),Color.rgb(255,165,45),Color.rgb(135,77,220)};
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;double b=x.optDouble("budget",0),sp=x.optDouble("spent",0);
            LinearLayout c=softCard(Color.WHITE,Color.rgb(230,235,240));LinearLayout h=horizontal();TextView n=text(x.optString("cat","Catégorie"),11,true);n.setTextColor(Color.rgb(8,35,70));h.addView(n,new LinearLayout.LayoutParams(0,-2,1));TextView v=text(money(sp)+" / "+money(b),10,true);v.setTextColor(sp>b?Color.rgb(210,45,60):Color.rgb(0,145,84));h.addView(v);c.addView(h);c.addView(progressLine(b>0?sp/b*100:0,cs[i%cs.length]));content.addView(c);}
    }

    private void renderGoalsMobile(){
        screenTitle("Objectifs financiers");JSONArray a=snapshot==null?null:snapshot.optJSONArray("goals");if(empty(a)){emptyState("Aucun objectif.");return;}
        String[] icons={"⌂","▣","●","▤","✈"};int i=0;
        for(;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;double target=x.optDouble("price",0),saved=x.optDouble("saved",0),pct=target>0?Math.min(100,saved/target*100):0;
            LinearLayout c=softCard(Color.WHITE,Color.rgb(230,235,240));LinearLayout r=horizontal();TextView ic=text(icons[i%icons.length],18,true);ic.setGravity(Gravity.CENTER);ic.setTextColor(Color.rgb(0,166,96));r.addView(ic,new LinearLayout.LayoutParams(dp(42),dp(42)));
            LinearLayout mid=vertical();TextView n=text(x.optString("name","Objectif"),12,true);n.setTextColor(Color.rgb(8,35,70));mid.addView(n);TextView v=text(money(saved)+" / "+money(target),10,false);v.setTextColor(Color.rgb(75,88,102));mid.addView(v);mid.addView(progressLine(pct,Color.rgb(0,166,96)));r.addView(mid,new LinearLayout.LayoutParams(0,-2,1));TextView p=text(Math.round(pct)+"%",10,true);p.setTextColor(Color.rgb(0,145,84));r.addView(p);c.addView(r);content.addView(c);}
    }

    private void renderProjectionMobile(){
        screenTitle("Projection - "+(snapshot==null?"":snapshot.optString("period","")));JSONObject sm=snapshot==null?null:snapshot.optJSONObject("summary");if(sm==null){emptyState("Projection non disponible.");return;}
        double inc=sm.optDouble("incomeExpected",0),exp=sm.optDouble("expensePlanned",0),bal=inc-exp;LinearLayout top=softCard(Color.WHITE,Color.rgb(225,232,240));LinearLayout r=horizontal();
        r.addView(compactMetric("Revenus prévus",money(inc),Color.rgb(0,150,83)),weight());r.addView(compactMetric("Dépenses prévues",money(exp),Color.rgb(215,48,62)),weight());r.addView(compactMetric("Solde projeté",money(bal),bal>=0?Color.rgb(0,150,83):Color.rgb(215,48,62)),weight());top.addView(r);content.addView(top);
        JSONArray a=snapshot.optJSONArray("monthlySeries");if(a!=null&&a.length()>0){content.addView(sectionLabel("Évolution"));content.addView(new ProjectionChartView(a),new LinearLayout.LayoutParams(-1,dp(220)));}
    }

    private void renderReportsMobile(){
        screenTitle("Analyses");LinearLayout tabs=horizontal();for(String x:new String[]{"Par catégorie","Par personne","Évolution"})tabs.addView(analysisFilterChip(x),weight());content.addView(tabs);
        if("Évolution".equals(analysisFilter)){JSONArray ms=snapshot==null?null:snapshot.optJSONArray("monthlySeries");if(ms==null||ms.length()==0){emptyState("Aucune donnée d’évolution.");return;}content.addView(new ProjectionChartView(ms),new LinearLayout.LayoutParams(-1,dp(230)));return;}
        if("Par personne".equals(analysisFilter)){renderAnalysisByPerson();return;}
        renderAnalysisByCategory();
    }
    private View analysisFilterChip(String label){View v=filterChip(label,label.equals(analysisFilter));v.setClickable(true);v.setOnClickListener(x->{analysisFilter=label;showSection("Rapports");});return v;}
    private void renderAnalysisByCategory(){
        JSONObject sm=snapshot==null?null:snapshot.optJSONObject("summary");double total=sm==null?0:sm.optDouble("expenseActual",0);JSONArray b=snapshot==null?null:snapshot.optJSONArray("budgets");List<Double> vals=new ArrayList<>();List<String> labels=new ArrayList<>();double sum=0;
        if(b!=null)for(int i=0;i<b.length()&&i<7;i++){JSONObject x=b.optJSONObject(i);if(x==null)continue;double v=Math.max(0,x.optDouble("spent",0));if(v<=0)continue;vals.add(v);labels.add(x.optString("cat","Autres"));sum+=v;}if(vals.isEmpty()){vals.add(Math.max(1,total));labels.add("Dépenses");sum=Math.max(1,total);}content.addView(analysisDonutCard(vals,labels,sum,total));
    }
    private void renderAnalysisByPerson(){
        JSONArray tx=snapshot==null?null:snapshot.optJSONArray("transactions");double h=0,f=0,c=0;
        if(tx!=null)for(int i=0;i<tx.length();i++){JSONObject x=tx.optJSONObject(i);if(x==null)continue;double v=x.optDouble("amount",0);if(v>=0)continue;v=Math.abs(v);String o=x.optString("owner","Commun");if("Homme".equalsIgnoreCase(o))h+=v;else if("Femme".equalsIgnoreCase(o))f+=v;else c+=v;}
        List<Double> vals=new ArrayList<>();List<String> labels=new ArrayList<>();if(h>0){vals.add(h);labels.add("Homme");}if(f>0){vals.add(f);labels.add("Femme");}if(c>0){vals.add(c);labels.add("Commun");}double sum=h+f+c;if(vals.isEmpty()){emptyState("Aucune dépense par personne disponible.");return;}content.addView(analysisDonutCard(vals,labels,sum,sum));
    }
    private View analysisDonutCard(List<Double> vals,List<String> labels,double sum,double total){
        LinearLayout card=softCard(Color.WHITE,Color.rgb(225,232,240));LinearLayout body=horizontal();body.addView(new ExpenseDonutView(vals,sum,total),new LinearLayout.LayoutParams(dp(165),dp(165)));LinearLayout leg=vertical();int[] cs={Color.rgb(35,147,235),Color.rgb(245,83,110),Color.rgb(255,179,48),Color.rgb(0,166,96),Color.rgb(132,79,221),Color.rgb(70,194,120)};
        for(int i=0;i<vals.size();i++){TextView l=text("● "+labels.get(i)+"  "+Math.round(vals.get(i)/Math.max(1,sum)*100)+"%  "+money(vals.get(i)),9,false);l.setTextColor(cs[i%cs.length]);leg.addView(l);}body.addView(leg,new LinearLayout.LayoutParams(0,-2,1));card.addView(body);return card;
    }

    private LinearLayout softCard(int bg,int stroke){LinearLayout c=vertical();c.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable g=new GradientDrawable();g.setColor(bg);g.setCornerRadius(dp(16));g.setStroke(dp(1),stroke);c.setBackground(g);c.setElevation(dp(1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(2),dp(5),dp(2),dp(5));c.setLayoutParams(p);return c;}
    private Button smallPill(String x){Button b=button(x);b.setTextSize(10);b.setTextColor(Color.rgb(18,82,170));b.setPadding(dp(5),dp(3),dp(5),dp(3));GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(239,246,255));g.setCornerRadius(dp(14));g.setStroke(dp(1),Color.rgb(221,232,246));b.setBackground(g);return b;}

    private void addCountSummary(String label, String key) {
        JSONArray a = snapshot.optJSONArray(key);
        content.addView(infoCard(label, (a == null ? 0 : a.length()) + " élément(s) dans la vue PC."));
    }

    private void renderAccounts() {
        screenTitle("Comptes");
        LinearLayout filters=horizontal();for(String x:new String[]{"Tous","Homme","Femme","Commun"})filters.addView(accountFilterChip(x),weight());content.addView(filters);
        JSONArray a=snapshot.optJSONArray("accounts");if(empty(a)){emptyState("Aucun compte.");return;}
        double current=0,credit=0,savings=0;int visible=0;
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null||!accountMatches(x))continue;visible++;String type=x.optString("type","").toLowerCase(Locale.CANADA_FRENCH);double bal=x.optDouble("balance",0);if(isCreditType(type))credit+=bal;else if(isSavingsType(type))savings+=bal;else current+=bal;}
        if(visible==0){emptyState("Aucun compte pour "+accountFilter+".");return;}
        content.addView(accountGroupTitle("Comptes courants",money(current),current>=0));
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null||!accountMatches(x))continue;String type=x.optString("type","").toLowerCase(Locale.CANADA_FRENCH);if(!isCreditType(type)&&!isSavingsType(type))content.addView(accountRow(x,false));}
        content.addView(accountGroupTitle("Cartes de crédit",money(credit),credit>=0));
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null||!accountMatches(x))continue;String type=x.optString("type","").toLowerCase(Locale.CANADA_FRENCH);if(isCreditType(type))content.addView(accountRow(x,true));}
        content.addView(accountGroupTitle("Épargne",money(savings),true));
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null||!accountMatches(x))continue;String type=x.optString("type","").toLowerCase(Locale.CANADA_FRENCH);if(isSavingsType(type))content.addView(accountRow(x,false));}
    }
    private View accountFilterChip(String label){View v=filterChip(label,label.equals(accountFilter));v.setClickable(true);v.setOnClickListener(x->{accountFilter=label;showSection("Comptes");});return v;}
    private boolean accountMatches(JSONObject x){return "Tous".equals(accountFilter)||accountFilter.equalsIgnoreCase(x.optString("owner","Commun"));}
    private boolean isCreditType(String t){return t.contains("carte")||t.contains("crédit")||t.contains("credit");}
    private boolean isSavingsType(String t){return t.contains("épargne")||t.contains("epargne")||t.contains("celi")||t.contains("reee");}

    private View accountGroupTitle(String title,String value,boolean positive){
        LinearLayout r=horizontal();r.setPadding(dp(3),dp(12),dp(3),dp(4));TextView t=text(title,13,true);t.setTextColor(Color.rgb(8,35,70));r.addView(t,new LinearLayout.LayoutParams(0,-2,1));TextView v=text(value,12,true);v.setTextColor(positive?Color.rgb(0,135,75):Color.rgb(210,45,60));r.addView(v);return r;
    }

    private View accountRow(JSONObject x,boolean card){
        LinearLayout r=horizontal();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(9),dp(9),dp(9),dp(9));GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(10));g.setStroke(dp(1),Color.rgb(230,235,240));r.setBackground(g);
        TextView ic=text(card?"▣":"▤",17,true);ic.setGravity(Gravity.CENTER);ic.setTextColor(card?Color.rgb(35,132,235):Color.rgb(0,166,96));r.addView(ic,new LinearLayout.LayoutParams(dp(40),dp(42)));
        LinearLayout mid=vertical();TextView n=text(x.optString("owner","Commun")+" — "+x.optString("institution","")+" — "+x.optString("name","Compte"),10,true);n.setTextColor(Color.rgb(8,35,70));mid.addView(n);
        if(card){double min=x.optDouble("minPayment",0),rem=x.optDouble("minPaymentRemaining",min);TextView m=text("Minimum : "+money(min)+(rem<min?" • Reste : "+money(rem):""),9,false);m.setTextColor(Color.GRAY);mid.addView(m);}
        r.addView(mid,new LinearLayout.LayoutParams(0,-2,1));double bal=x.optDouble("balance",0);TextView v=text(money(bal),11,true);v.setGravity(Gravity.RIGHT);v.setTextColor(bal<0?Color.rgb(215,48,62):Color.rgb(8,35,70));r.addView(v,new LinearLayout.LayoutParams(dp(104),-2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(2),0,dp(2));r.setLayoutParams(lp);return r;
    }

    private void renderIncome() {
        JSONObject s=snapshot.optJSONObject("summary");
        if(s!=null)content.addView(kpiCard("Revenus du mois",money(s.optDouble("incomeReceived",0)),"À recevoir : "+money(s.optDouble("incomeExpected",0)),true));
        content.addView(sectionLabel("Sources de revenus"));
        renderArray("incomeSources", 50, x ->
                x.optString("owner","Commun")+" • "+x.optString("name","Revenu"),
                x -> "Type : "+x.optString("type","")+" • Fréquence : "+x.optString("frequency","")+"\nMontant : "+money(x.optDouble("lastAmount",0))+" • "+(x.optBoolean("active",true)?"Active":"Inactive"));
        content.addView(sectionLabel("Historique des revenus"));
        renderArrayReverse("incomeEntries", 80, x ->
                x.optString("date","")+" • "+x.optString("owner","Commun")+" • "+x.optString("sourceName",x.optString("source","Revenu")),
                x -> money(x.optDouble("amount",0))+" • "+x.optString("status",""));
        content.addView(sectionLabel("RQAP & allocations"));
        renderArray("benefits", 50, x ->
                x.optString("name",x.optString("type","Prestation")),
                x -> x.optString("owner","Commun")+" • "+x.optString("type","")+"\nMontant : "+money(x.optDouble("amount",x.optDouble("weeklyIncome",0)))+" • "+(x.optBoolean("active",true)?"Active":"Inactive"));
    }

    private void renderExpenses() {
        JSONObject sm=snapshot.optJSONObject("summary");
        if(sm!=null)content.addView(kpiCard("Dépenses du mois",money(sm.optDouble("expenseActual",0)),"Total prévu : "+money(sm.optDouble("expensePlanned",0)),false));
        content.addView(sectionLabel("Budget"));
        renderArray("budgets", 60, x ->
                x.optString("cat","Catégorie"),
                x -> "Réel : "+money(x.optDouble("spent",0))+" • Prévu : "+money(x.optDouble("budget",0)));

        content.addView(sectionLabel("Dépenses constantes / paiements"));
        JSONArray a=snapshot.optJSONArray("recurringView");
        if(a==null){
            renderArray("recurring", 60, x ->
                    x.optString("name","Dépense constante"),
                    x -> x.optString("owner","Commun")+" • "+x.optString("cat","Autres")+"\n"+money(x.optDouble("amount",0)));
            return;
        }
        if(a.length()==0){emptyState("Aucune dépense constante active.");return;}
        for(int i=0;i<a.length();i++){
            final JSONObject x=a.optJSONObject(i); if(x==null)continue;
            LinearLayout c=cardBox();
            TextView t=text(x.optString("name","Dépense constante"),15,true);t.setTextColor(Color.rgb(7,51,94));c.addView(t);
            String state=x.optBoolean("posted",false)?"Prise en compte":(x.optBoolean("deferred",false)?"Reportée":"À venir");
            String body=x.optString("owner","Commun")+" • "+x.optString("category","Autres")+"\nMontant / reste : "+money(x.optDouble("amount",0))+
                    "\nÉchéance : "+x.optString("dueDate","—")+" • "+state;
            if(!x.isNull("minimumRemaining"))body+="\nMinimum carte restant : "+money(x.optDouble("minimumRemaining",0));
            TextView b=text(body,12,false);b.setTextColor(Color.DKGRAY);b.setPadding(0,dp(4),0,dp(6));c.addView(b);
            Button report=button(x.optBoolean("posted",false)?"Reporter / retirer du mois":"Reporter");
            report.setOnClickListener(v->chooseDeferralDate(x));
            c.addView(report,new LinearLayout.LayoutParams(-1,-2));
            content.addView(c);
        }
    }

    private void chooseDeferralDate(JSONObject rec) {
        String due=rec.optString("dueDate", isoDate());
        Calendar cal=Calendar.getInstance();
        try{
            Date d=new SimpleDateFormat("yyyy-MM-dd",Locale.CANADA_FRENCH).parse(due);
            if(d!=null)cal.setTime(d);
        }catch(Exception ignored){}
        new DatePickerDialog(this,(v,y,m,d)->{
            String nd=String.format(Locale.CANADA_FRENCH,"%04d-%02d-%02d",y,m+1,d);
            writeDeferralCommand(rec,nd);
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private boolean ensureWorkbookForWrite() {
        if(treeUri==null && workbookUri==null){toast("Choisis d'abord le fichier Excel OneDrive ou Google Drive.");return false;}
        try{
            if(treeUri!=null){locateFiles();if(workbookUri==null)ensureWorkbook();}
            if(workbookUri==null)throw new Exception("CoupleFinance_Mobile.xlsx n'est pas sélectionné.");
            return true;
        }catch(Exception e){toast("Excel mobile inaccessible : "+e.getMessage());return false;}
    }

    private void writeDeferralCommand(JSONObject rec,String newDate) {
        if(!ensureWorkbookForWrite())return;
        XlsxAppender.Entry e=new XlsxAppender.Entry();
        e.date=isoDate();e.time=hmTime();e.owner=rec.optString("owner","Commun");e.type="Reporter paiement";e.amount=0;
        e.category="Report";e.description=rec.optString("name","Paiement reporté");e.account="";
        e.note="RECURRENCE_ID="+rec.optString("id","")+"; NEWDATE="+newDate+"; PERIOD="+rec.optString("period","");
        new Thread(()->{
            try{
                XlsxAppender.append(getContentResolver(),workbookUri,e);
                runOnUiThread(()->{recordPhoneMovement(e,"Envoyé vers Excel");toast("Report envoyé au PC pour le "+newDate+".");});
            }catch(Exception ex){runOnUiThread(()->toast("Erreur d'écriture : "+ex.getMessage()));}
        }).start();
    }

    private void renderTransactions() {
        JSONArray a=snapshot.optJSONArray("transactions");
        if(empty(a)){emptyState("Aucune transaction.");return;}
        int shown=0;
        for(int i=a.length()-1;i>=0 && shown<120;i--,shown++){
            JSONObject x=a.optJSONObject(i);if(x==null)continue;
            double amt=x.optDouble("amount",0);
            String body=x.optString("owner","Commun")+" • "+x.optString("cat","Autres")+" • "+x.optString("origin",x.optString("source",""))+
                    "\n"+(amt<0?"− ":"+ ")+money(Math.abs(amt))+" • "+x.optString("operation",x.optString("type","Transaction"));
            content.addView(infoCard(x.optString("date","")+" • "+x.optString("desc","Transaction"),body));
        }
        content.addView(sectionLabel("Transferts de comptes"));
        renderArrayReverse("accountTransfers",50,x ->
                x.optString("date","")+" • Transfert",
                x -> money(x.optDouble("amount",0))+" • "+x.optString("note",""));
    }

    private void renderDebtsGoals() {
        screenTitle("Dettes");LinearLayout filters=horizontal();for(String x:new String[]{"Toutes","Carte de crédit","Prêt","Autres"})filters.addView(debtFilterChip(x),weight());content.addView(filters);
        JSONArray accounts=snapshot.optJSONArray("accounts"),debts=snapshot.optJSONArray("debts");if(empty(accounts)&&empty(debts)){emptyState("Aucune dette.");return;}int shown=0;
        if(("Toutes".equals(debtFilter)||"Carte de crédit".equals(debtFilter))&&accounts!=null)for(int i=0;i<accounts.length();i++){JSONObject a=accounts.optJSONObject(i);if(a==null)continue;String type=a.optString("type","").toLowerCase(Locale.CANADA_FRENCH);if(isCreditType(type)){content.addView(creditDebtCard(a,debts));shown++;}}
        if(debts!=null)for(int i=0;i<debts.length();i++){JSONObject d=debts.optJSONObject(i);if(d==null)continue;String aid=d.optString("sourceAccountId","");boolean linked=false;if(accounts!=null)for(int j=0;j<accounts.length();j++){JSONObject a=accounts.optJSONObject(j);if(a!=null&&aid.equals(a.optString("id",""))){linked=true;break;}}if(linked)continue;
            String typ=(d.optString("type","")+" "+d.optString("name","")).toLowerCase(Locale.CANADA_FRENCH);boolean loan=typ.contains("prêt")||typ.contains("pret")||typ.contains("loan")||typ.contains("accord");
            if("Carte de crédit".equals(debtFilter))continue;if("Prêt".equals(debtFilter)&&!loan)continue;if("Autres".equals(debtFilter)&&loan)continue;
            if(shown==0||shown>0)content.addView(infoCard(d.optString("owner","Commun")+" — "+d.optString("name","Dette"),debtBody(d)));shown++;}
        if(shown==0)emptyState("Aucune dette dans « "+debtFilter+" ».");
    }
    private View debtFilterChip(String label){View v=filterChip(label,label.equals(debtFilter));v.setClickable(true);v.setOnClickListener(x->{debtFilter=label;showSection("Dettes");});return v;}

    private View creditDebtCard(JSONObject a,JSONArray debts){
        LinearLayout c=softCard(Color.WHITE,Color.rgb(225,232,240));TextView n=text(a.optString("owner","Commun")+" — "+a.optString("institution","")+" — "+a.optString("name","Carte"),12,true);n.setTextColor(Color.rgb(8,35,70));c.addView(n);
        double bal=a.optDouble("balance",0),min=a.optDouble("minPayment",0),rem=a.optDouble("minPaymentRemaining",min),linked=0;String aid=a.optString("id","");
        if(debts!=null)for(int i=0;i<debts.length();i++){JSONObject d=debts.optJSONObject(i);if(d!=null&&aid.equals(d.optString("sourceAccountId","")))linked+=Math.min(Math.max(0,d.optDouble("balance",0)),Math.max(0,d.optDouble("min",d.optDouble("minimum",0))));}
        double own=Math.max(0,min-linked);
        LinearLayout line=horizontal();line.addView(compactMetric("Solde actuel",money(bal),bal<0?Color.rgb(215,48,62):Color.rgb(8,35,70)),weight());line.addView(compactMetric("Minimum mensuel",money(min),Color.rgb(8,35,70)),weight());c.addView(line);
        TextView due=text("Échéance : "+a.optString("dueDate","—"),10,false);due.setTextColor(Color.rgb(75,88,102));c.addView(due);
        LinearLayout split=softCard(Color.rgb(249,251,253),Color.rgb(230,235,240));TextView st=text("Répartition du minimum",11,true);st.setTextColor(Color.rgb(8,35,70));split.addView(st);split.addView(text("• Dette liée : "+money(linked),10,false));split.addView(text("• Part carte : "+money(own),10,false));split.addView(text("• Total minimum : "+money(min),10,true));c.addView(split);
        boolean covered=rem<=0.005;LinearLayout status=softCard(covered?Color.rgb(234,249,240):Color.rgb(255,247,232),covered?Color.rgb(196,233,208):Color.rgb(244,218,170));TextView ss=text(covered?"✓ Minimum du mois : 0,00 $":"Minimum restant : "+money(rem),11,true);ss.setTextColor(covered?Color.rgb(0,130,72):Color.rgb(176,104,0));status.addView(ss);TextView sb=text(covered?"Paiement minimum satisfait. Le solde restant n'est plus urgent pour ce mois.":"Un paiement reste requis pour satisfaire le minimum du mois.",9,false);sb.setTextColor(Color.rgb(75,88,102));status.addView(sb);c.addView(status);return c;
    }

    private String debtBody(JSONObject x){
        String body="Solde : "+money(x.optDouble("balance",0))+" • Taux : "+fmt(x.optDouble("rate",0))+" %\nMinimum : "+money(x.optDouble("min",0))+" • Échéance : "+x.optString("dueDate","—");
        String aid=x.optString("sourceAccountId","");
        JSONArray acc=snapshot==null?null:snapshot.optJSONArray("accounts");
        if(acc!=null&&!aid.isEmpty())for(int i=0;i<acc.length();i++){JSONObject a=acc.optJSONObject(i);if(a!=null&&aid.equals(a.optString("id",""))&&!a.isNull("minPaymentRemaining")){body+="\nReste minimum ce mois : "+money(a.optDouble("minPaymentRemaining",0));break;}}
        return body;
    }

    private void renderSavings() {
        content.addView(sectionLabel("Placements"));
        renderArray("investments",80,x ->
                x.optString("owner","Commun")+" • "+x.optString("name","Placement"),
                x -> x.optString("type","")+" • "+x.optString("institution","")+"\nValeur : "+money(x.optDouble("current",0))+" • Contribution : "+money(x.optDouble("contribution",0))+" "+x.optString("frequency",""));
        content.addView(sectionLabel("Objectifs d'épargne"));
        renderArray("goals",80,x ->
                x.optString("name","Objectif"),
                x -> "Valeur cible : "+money(x.optDouble("price",0))+" • Déjà accumulé : "+money(x.optDouble("saved",0)));
    }

    private void renderAgenda() {
        content.addView(sectionLabel("Tâches du mois"));
        renderArray("monthTasks",100,x ->
                x.optString("date","")+" • "+x.optString("name","Tâche"),
                x -> x.optString("owner","Commun")+" • "+(x.optBoolean("done",false)?"Fait":"À faire")+" • "+money(x.optDouble("amount",0)));
        content.addView(sectionLabel("Événements"));
        renderArray("agendaEvents",100,x ->
                x.optString("date","")+" • "+x.optString("name","Événement"),
                x -> x.optString("owner","Commun")+" • "+x.optString("status","Prévu")+" • "+money(x.optDouble("amount",0)));
        content.addView(sectionLabel("À venir"));
        renderArray("upcoming",100,x ->
                x.optString("date","")+" • "+x.optString("name","À venir"),
                x -> x.optString("cat","")+" • "+money(x.optDouble("amount",0)));
    }

    private interface TitleMaker { String make(JSONObject x); }
    private interface BodyMaker { String make(JSONObject x); }

    private void renderArray(String key,int max,TitleMaker tm,BodyMaker bm){
        JSONArray a=snapshot.optJSONArray(key);
        if(empty(a)){emptyState("Aucune donnée.");return;}
        for(int i=0;i<a.length()&&i<max;i++){JSONObject x=a.optJSONObject(i);if(x!=null)content.addView(infoCard(tm.make(x),bm.make(x)));}
    }
    private void renderArrayReverse(String key,int max,TitleMaker tm,BodyMaker bm){
        JSONArray a=snapshot.optJSONArray(key);
        if(empty(a)){emptyState("Aucune donnée.");return;}
        int shown=0;for(int i=a.length()-1;i>=0&&shown<max;i--,shown++){JSONObject x=a.optJSONObject(i);if(x!=null)content.addView(infoCard(tm.make(x),bm.make(x)));}
    }

    private void buildEntryForm() {
        screenTitle("Ajouter un mouvement");
        entryType=new Spinner(this);entryType.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Dépense","Revenu","Transaction","Remboursement crédit","Épargne","Événement"}));entryType.setVisibility(View.GONE);content.addView(entryType);
        LinearLayout types=horizontal();
        Button b0=typeButton("▣\nDépense",Color.rgb(255,234,236),Color.rgb(220,45,60));b0.setOnClickListener(v->selectEntryType(0));types.addView(b0,weight());
        Button b1=typeButton("＋\nRevenu",Color.rgb(232,249,238),Color.rgb(0,150,83));b1.setOnClickListener(v->selectEntryType(1));types.addView(b1,weight());
        Button b2=typeButton("▤\nRembours.",Color.rgb(255,241,226),Color.rgb(230,120,15));b2.setOnClickListener(v->selectEntryType(3));types.addView(b2,weight());
        Button b3=typeButton("⇄\nTransfert",Color.rgb(244,237,255),Color.rgb(119,64,201));b3.setOnClickListener(v->selectEntryType(4));types.addView(b3,weight());content.addView(types);

        date=input(isoDate());date.setFocusable(false);date.setOnClickListener(v->pickDate());addLabeled(content,"Date",date);
        amount=input("0,00 $");amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);addLabeled(content,"Montant",amount);
        category=new Spinner(this);category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,categoryLabels()));addLabeled(content,"Catégorie",category);
        sourceAccount=new Spinner(this);sourceAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accountLabels(true)));addLabeled(content,"Compte",sourceAccount);
        description=input("Ex. Metro, Walmart, salaire...");addLabeled(content,"Description",description);

        owner=new Spinner(this);owner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Homme","Femme","Commun"}));owner.setSelection(2);addLabeled(content,"Personne",owner);
        time=input(hmTime());time.setVisibility(View.GONE);content.addView(time);

        destinationBlock=vertical();destinationAccount=new Spinner(this);destinationAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accountLabels(false)));addLabeled(destinationBlock,"Compte destination",destinationAccount);content.addView(destinationBlock);destinationBlock.setVisibility(View.GONE);
        debtBlock=vertical();debtAccount=new Spinner(this);debtAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,debtLabels()));addLabeled(debtBlock,"Carte / dette à rembourser",debtAccount);content.addView(debtBlock);debtBlock.setVisibility(View.GONE);

        note=input("Optionnel");note.setMinLines(2);note.setSingleLine(false);note.setGravity(Gravity.TOP);addLabeled(content,"Note",note);
        Button receipt=typeButton("▣  Ajouter une photo du reçu",Color.rgb(247,249,252),Color.rgb(70,88,106));receipt.setOnClickListener(v->toast("La photo du reçu sera ajoutée dans une prochaine étape sans modifier l'écriture Excel actuelle."));content.addView(receipt,new LinearLayout.LayoutParams(-1,dp(52)));

        entryType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){String type=String.valueOf(entryType.getSelectedItem());destinationBlock.setVisibility("Épargne".equals(type)?View.VISIBLE:View.GONE);debtBlock.setVisibility("Remboursement crédit".equals(type)?View.VISIBLE:View.GONE);}public void onNothingSelected(android.widget.AdapterView<?> p){}});

        saveButton=primaryButton("Enregistrer");saveButton.setTextSize(15);saveButton.setPadding(dp(10),dp(14),dp(10),dp(14));saveButton.setOnClickListener(v->saveEntry());content.addView(saveButton,new LinearLayout.LayoutParams(-1,dp(58)));
    }

    private void selectEntryType(int index){if(entryType==null)return;if(index<0||index>=entryType.getCount())index=0;entryType.setSelection(index);toast("Type sélectionné : "+String.valueOf(entryType.getItemAtPosition(index)));}

    private Button typeButton(String label,int bg,int fg){Button b=button(label);b.setTextSize(9);b.setTextColor(fg);b.setTypeface(null,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setPadding(dp(3),dp(6),dp(3),dp(6));GradientDrawable g=new GradientDrawable();g.setColor(bg);g.setCornerRadius(dp(12));g.setStroke(dp(1),Color.rgb(230,235,240));b.setBackground(g);return b;}

    private List<String> categoryLabels(){
        List<String> out=new ArrayList<>();out.add("Autres");
        if(snapshot!=null){JSONArray a=snapshot.optJSONArray("categories");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null){String n=x.optString("name","");if(!n.isEmpty()&&!out.contains(n))out.add(n);}}}
        return out;
    }
    private List<String> accountLabels(boolean optional){
        List<String> out=new ArrayList<>();if(optional)out.add("— Non lié / autre —");
        if(snapshot!=null){JSONArray a=snapshot.optJSONArray("accounts");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null){String n=x.optString("owner","Commun")+" — "+x.optString("institution","")+" — "+x.optString("name","Compte");out.add(n);}}}
        if(out.isEmpty())out.add("Compte non défini");
        return out;
    }

    private List<String> debtLabels(){
        debtIds.clear();List<String> out=new ArrayList<>();
        if(snapshot!=null){JSONArray a=snapshot.optJSONArray("debts");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&x.optDouble("balance",0)>0){debtIds.add(x.optString("id",""));out.add(x.optString("owner","Commun")+" — "+x.optString("name","Dette")+" — "+money(x.optDouble("balance",0)));}}}
        if(out.isEmpty()){debtIds.add("");out.add("Aucune dette disponible");}
        return out;
    }

    private String selectedDebtId(){
        int p=debtAccount==null?0:debtAccount.getSelectedItemPosition();
        return (p>=0&&p<debtIds.size())?debtIds.get(p):"";
    }

    private void saveEntry() {
        if(!ensureWorkbookForWrite())return;
        String uiType=String.valueOf(entryType.getSelectedItem());
        String desc=description.getText().toString().trim();
        if(desc.isEmpty())desc=uiType+" téléphone";
        double a=0;try{a=Double.parseDouble(amount.getText().toString().trim().replace(',','.'));}catch(Exception ignored){}
        if(!"Événement".equals(uiType)&&a<=0){toast("Indiquez un montant supérieur à 0.");return;}

        String type=uiType;
        if("Transaction".equals(uiType)) type="Dépense"; // transaction simple = retrait; revenu a son propre bouton.

        XlsxAppender.Entry e=new XlsxAppender.Entry();
        e.date=date.getText().toString();
        e.time=time.getText().toString();
        e.owner=String.valueOf(owner.getSelectedItem());
        e.type=type;
        e.amount=a;
        e.category=String.valueOf(category.getSelectedItem());
        e.description=desc;
        String src=String.valueOf(sourceAccount.getSelectedItem());
        if(src.startsWith("—"))src="";
        e.account=src;
        String userNote=note.getText().toString().trim();
        if("Épargne".equals(uiType)){
            String dest=String.valueOf(destinationAccount.getSelectedItem());
            if(dest.isEmpty()||dest.equals(src)){toast("Choisissez un compte destination différent.");return;}
            e.category="Épargne";
            e.note="DESTINATION="+dest+"; "+userNote;
        }else if("Remboursement crédit".equals(uiType)){
            String did=selectedDebtId();
            if(did.isEmpty()){toast("Choisissez une carte ou une dette à rembourser.");return;}
            e.type="Remboursement crédit";
            e.category="Remboursement dette supplémentaire";
            e.note="DEBT_ID="+did+"; "+userNote;
        }else e.note=userNote;

        saveButton.setEnabled(false);
        new Thread(()->{
            try{
                XlsxAppender.append(getContentResolver(),workbookUri,e);
                runOnUiThread(()->{
                    recordPhoneMovement(e,"Envoyé vers Excel");
                    saveButton.setEnabled(true);amount.setText("");description.setText("");note.setText("");time.setText(hmTime());
                    toast("Saisie enregistrée. Couple Finance PC l'importera.");
                });
            }catch(Exception ex){
                runOnUiThread(()->{saveButton.setEnabled(true);toast("Erreur d'écriture : "+ex.getMessage());});
            }
        }).start();
    }

    private JSONArray loadPhoneHistory(){
        try{
            String raw=getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_PHONE_HISTORY,"[]");
            return new JSONArray(raw==null||raw.trim().isEmpty()?"[]":raw);
        }catch(Exception e){return new JSONArray();}
    }

    private void recordPhoneMovement(XlsxAppender.Entry e,String status){
        try{
            JSONArray old=loadPhoneHistory();JSONArray out=new JSONArray();
            JSONObject x=new JSONObject();
            x.put("savedAt",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CANADA_FRENCH).format(new Date()));
            x.put("date",e.date);x.put("time",e.time);x.put("owner",e.owner);x.put("type",e.type);
            x.put("amount",e.amount);x.put("category",e.category);x.put("description",e.description);
            x.put("account",e.account);x.put("note",e.note);x.put("status",status);
            out.put(x);
            for(int i=0;i<old.length()&&i<249;i++)out.put(old.opt(i));
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(PREF_PHONE_HISTORY,out.toString()).apply();
        }catch(Exception ignored){}
    }

    private void renderPhoneHistory(boolean compact){
        JSONArray a=loadPhoneHistory();
        LinearLayout box=softCard(Color.WHITE,Color.rgb(226,232,240));
        LinearLayout hr=horizontal();hr.setGravity(Gravity.CENTER_VERTICAL);
        TextView h=text(compact?"Mouvements faits depuis le téléphone":"Historique des mouvements téléphone",compact?17:22,true);
        h.setTextColor(Color.rgb(7,51,94));hr.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        if(compact){Button all=smallPill("Voir tout ›");all.setOnClickListener(v->showSection("Historique téléphone"));hr.addView(all,new LinearLayout.LayoutParams(dp(92),dp(40)));}
        else{Button clear=smallPill("Effacer l'historique");clear.setOnClickListener(v->{getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(PREF_PHONE_HISTORY).apply();showSection("Historique téléphone");toast("Historique local effacé.");});hr.addView(clear,new LinearLayout.LayoutParams(dp(126),dp(40)));}
        box.addView(hr);

        TextView hint=text("Historique local du téléphone • conservé même après fermeture de l'application",10,false);hint.setTextColor(Color.GRAY);box.addView(hint);
        if(a.length()==0){
            TextView e=text("Aucun mouvement saisi depuis ce téléphone.",11,false);e.setTextColor(Color.GRAY);e.setPadding(0,dp(10),0,dp(8));box.addView(e);
        }else{
            int limit=compact?4:Math.min(250,a.length());
            for(int i=0;i<limit;i++){
                JSONObject x=a.optJSONObject(i);if(x==null)continue;
                LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));
                TextView icon=text(phoneMovementIcon(x.optString("type","")),20,true);icon.setGravity(Gravity.CENTER);icon.setTextColor(Color.rgb(18,102,210));row.addView(icon,new LinearLayout.LayoutParams(dp(38),dp(46)));
                LinearLayout info=vertical();
                String desc=x.optString("description",x.optString("type","Mouvement"));
                TextView t=text(desc,12,true);t.setTextColor(Color.rgb(7,45,84));info.addView(t);
                String meta=x.optString("date","")+" "+x.optString("time","")+" • "+x.optString("owner","Commun")+" • "+x.optString("category","");
                TextView m=text(meta,9,false);m.setTextColor(Color.GRAY);info.addView(m);
                String acc=x.optString("account","");if(!acc.isEmpty()){TextView ac=text(acc,9,false);ac.setTextColor(Color.rgb(83,100,120));info.addView(ac);}
                row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
                LinearLayout right=vertical();right.setGravity(Gravity.RIGHT);
                double amt=x.optDouble("amount",0);String typ=x.optString("type","");
                TextView val=text(("Revenu".equals(typ)?"+ ":"")+(amt>0?money(amt):"—"),11,true);val.setGravity(Gravity.RIGHT);val.setTextColor("Revenu".equals(typ)?Color.rgb(20,120,58):Color.rgb(7,45,84));right.addView(val);
                TextView st=text("✓ "+x.optString("status","Enregistré"),8,true);st.setGravity(Gravity.RIGHT);st.setTextColor(Color.rgb(20,120,58));right.addView(st);
                row.addView(right,new LinearLayout.LayoutParams(dp(108),-2));box.addView(row);
            }
        }
        content.addView(box);
        if(!compact){
            TextView note=text("Cet historique affiche uniquement les mouvements créés sur ce téléphone. Les opérations provenant du PC restent dans les pages Transactions et Dépenses.",10,false);
            note.setTextColor(Color.GRAY);note.setPadding(dp(3),dp(6),dp(3),dp(12));content.addView(note);
        }
    }

    private String phoneMovementIcon(String type){
        if("Revenu".equals(type))return "＋";
        if("Remboursement crédit".equals(type))return "▣";
        if("Épargne".equals(type))return "◎";
        if("Reporter paiement".equals(type))return "▦";
        if("Événement".equals(type))return "◷";
        return "−";
    }

    private void screenTitle(String title){
        TextView h=text(title,20,true);h.setGravity(Gravity.CENTER);h.setTextColor(Color.rgb(8,35,70));h.setPadding(dp(2),dp(4),dp(2),dp(8));content.addView(h);
    }

    private class ProjectionChartView extends View{
        private final JSONArray series;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ProjectionChartView(JSONArray a){super(MainActivity.this);series=a;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);int n=Math.min(6,series.length());if(n<=0)return;float w=getWidth(),h=getHeight(),left=dp(28),bottom=h-dp(28),top=dp(18),slot=(w-left-dp(10))/n;double max=1;
            for(int i=Math.max(0,series.length()-n);i<series.length();i++){JSONObject x=series.optJSONObject(i);if(x==null)continue;double in=x.optDouble("income",x.optDouble("hi",0)+x.optDouble("wi",0)+x.optDouble("ci",0));double ex=x.optDouble("expense",x.optDouble("he",0)+x.optDouble("we",0)+x.optDouble("ce",0));max=Math.max(max,Math.max(in,ex));}
            int start=Math.max(0,series.length()-n);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(9));
            for(int j=0;j<n;j++){JSONObject x=series.optJSONObject(start+j);if(x==null)continue;double in=x.optDouble("income",x.optDouble("hi",0)+x.optDouble("wi",0)+x.optDouble("ci",0));double ex=x.optDouble("expense",x.optDouble("he",0)+x.optDouble("we",0)+x.optDouble("ce",0));float cx=left+slot*j+slot/2;float bw=Math.max(dp(8),slot*.22f);float ih=(float)((bottom-top)*in/max),eh=(float)((bottom-top)*ex/max);
                p.setColor(Color.rgb(0,166,96));c.drawRoundRect(new RectF(cx-bw-dp(2),bottom-ih,cx-dp(2),bottom),dp(3),dp(3),p);p.setColor(Color.rgb(244,83,92));c.drawRoundRect(new RectF(cx+dp(2),bottom-eh,cx+bw+dp(2),bottom),dp(3),dp(3),p);
                p.setColor(Color.rgb(90,102,116));String lab=x.optString("period",x.optString("month","M"+(j+1)));if(lab.length()>5)lab=lab.substring(0,5);c.drawText(lab,cx,bottom+dp(16),p);}
        }
    }

    private class ExpenseDonutView extends View{
        private final List<Double> values;private final double sum;private final double total;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors={Color.rgb(51,143,238),Color.rgb(246,53,78),Color.rgb(91,174,100),Color.rgb(255,177,47),Color.rgb(132,79,221),Color.rgb(70,194,120)};
        ExpenseDonutView(List<Double> v,double s,double t){super(MainActivity.this);values=v;sum=s;total=t;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),pad=dp(12),size=Math.min(w,h)-2*pad;RectF r=new RectF((w-size)/2,(h-size)/2,(w+size)/2,(h+size)/2);float start=-90;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(22));p.setStrokeCap(Paint.Cap.BUTT);
            for(int i=0;i<values.size();i++){float sw=(float)(values.get(i)/Math.max(1,sum)*360.0);p.setColor(colors[i%colors.length]);c.drawArc(r,start,sw,false,p);start+=sw;}
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(7,45,84));p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(12));c.drawText(money(total),w/2,h/2,p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(9));c.drawText("Total",w/2,h/2+dp(16),p);
        }
    }

    private View kpiCard(String title,String value,String sub,boolean positive){
        LinearLayout c=cardBox();
        TextView t=text(title.toUpperCase(Locale.CANADA_FRENCH),11,true);t.setTextColor(Color.rgb(83,110,135));c.addView(t);
        TextView v=text(value,22,true);v.setTextColor(positive?Color.rgb(18,120,78):Color.rgb(173,49,57));c.addView(v);
        TextView s=text(sub,12,false);s.setTextColor(Color.DKGRAY);c.addView(s);
        return c;
    }
    private View infoCard(String title,String body){
        LinearLayout c=cardBox();
        TextView t=text(title,15,true);t.setTextColor(Color.rgb(7,51,94));c.addView(t);
        TextView b=text(body,12,false);b.setTextColor(Color.DKGRAY);b.setPadding(0,dp(4),0,0);c.addView(b);
        return c;
    }
    private TextView sectionLabel(String s){TextView t=text(s,16,true);t.setPadding(0,dp(14),0,dp(5));t.setTextColor(Color.rgb(7,51,94));return t;}
    private void emptyState(String s){content.addView(infoCard("Aucune donnée",s));}
    private boolean empty(JSONArray a){return a==null||a.length()==0;}

    private LinearLayout cardBox(){
        LinearLayout c=vertical();c.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(225,232,240));c.setBackground(g);c.setElevation(dp(2));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));c.setLayoutParams(p);return c;
    }
    private Button smallNavButton(String s){Button b=button(s);b.setTextSize(12);b.setPadding(dp(10),dp(5),dp(10),dp(5));return b;}
    private Button modernNavButton(String s){
        Button b=button(s);b.setTextSize(12);b.setTextColor(Color.rgb(7,51,94));b.setTypeface(null,Typeface.BOLD);b.setPadding(dp(6),dp(13),dp(6),dp(13));
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(14));g.setStroke(dp(1),Color.rgb(220,229,238));b.setBackground(g);b.setElevation(dp(1));return b;
    }
    private Button primaryButton(String s){
        Button b=button(s);b.setTextColor(Color.WHITE);b.setTypeface(null,Typeface.BOLD);
        GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(0,166,96));g.setCornerRadius(dp(14));b.setBackground(g);b.setElevation(dp(2));return b;
    }
    private String money(double n){return String.format(Locale.CANADA_FRENCH,"%,.2f $",n).replace('\u00A0',' ');}
    private String fmt(double n){return String.format(Locale.CANADA_FRENCH,"%.2f",n);}
    private void pickDate(){final Calendar c=Calendar.getInstance();new DatePickerDialog(this,(v,y,m,d)->date.setText(String.format(Locale.CANADA_FRENCH,"%04d-%02d-%02d",y,m+1,d)),c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void pickTime(){final Calendar c=Calendar.getInstance();new TimePickerDialog(this,(v,h,m)->time.setText(String.format(Locale.CANADA_FRENCH,"%02d:%02d",h,m)),c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show();}
    private static String isoDate(){return new SimpleDateFormat("yyyy-MM-dd",Locale.CANADA_FRENCH).format(new Date());}
    private static String hmTime(){return new SimpleDateFormat("HH:mm",Locale.CANADA_FRENCH).format(new Date());}

    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),dp(2),dp(2),dp(2));return p;}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);return e;}
    private void addLabeled(LinearLayout host,String label,View v){TextView l=text(label,12,true);l.setPadding(0,dp(8),0,0);host.addView(l);host.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private void spacer(LinearLayout host,int h){View v=new View(this);host.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}