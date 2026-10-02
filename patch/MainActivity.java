package com.couplefinance.mobile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
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
    private static final String PREFS = "cf_mobile_v2";
    private static final String PREF_TREE = "onedrive_tree_uri";
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
    private String currentSection = "Accueil";

    private Spinner entryType, owner, category, sourceAccount, destinationAccount;
    private EditText amount, date, time, description, note;
    private LinearLayout destinationBlock;
    private Button saveButton;

    private final String[] sections = new String[]{
            "Accueil","Comptes","Revenus","Dépenses","Transactions","Dettes","Épargne","Agenda","Saisie"
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (treeUri != null) {
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
        if (treeUri != null) {
            try { refreshSnapshot(false); } catch (Exception ignored) {}
        }
    }

    private View buildShell() {
        ScrollView outer = new ScrollView(this);
        root = vertical();
        root.setPadding(dp(14), dp(12), dp(14), dp(30));
        outer.addView(root);

        TextView title = text("♥ Couple Finance Mobile", 24, true);
        title.setTextColor(Color.rgb(7, 51, 94));
        root.addView(title);

        TextView sub = text("Consultation lecture seule + saisies vers Couple Finance PC", 12, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);
        spacer(root, 10);

        LinearLayout connectRow = horizontal();
        Button connect = button("Connecter OneDrive");
        connect.setOnClickListener(v -> chooseFolder());
        connectRow.addView(connect, weight());
        Button refresh = button("Actualiser");
        refresh.setOnClickListener(v -> refreshSnapshot(true));
        connectRow.addView(refresh, weight());
        root.addView(connectRow);

        connectionStatus = text("Aucun dossier connecté.", 12, false);
        connectionStatus.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(connectionStatus);

        syncStatus = text("", 11, false);
        syncStatus.setTextColor(Color.GRAY);
        root.addView(syncStatus);
        spacer(root, 8);

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = horizontal();
        for (String s : sections) {
            Button b = smallNavButton(s);
            b.setOnClickListener(v -> showSection(((Button)v).getText().toString()));
            nav.addView(b);
        }
        navScroll.addView(nav);
        root.addView(navScroll);
        spacer(root, 8);

        content = vertical();
        root.addView(content);
        showSection("Accueil");
        return outer;
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
        if (req != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
        treeUri = uri;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TREE, uri.toString()).apply();
        try {
            locateFiles();
            ensureWorkbook();
            refreshSnapshot(true);
        } catch (Exception e) {
            setConnection(false, "Erreur OneDrive : " + e.getMessage());
        }
    }

    private void restoreFolder() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE, "");
        if (s.isEmpty()) return;
        treeUri = Uri.parse(s);
        try {
            locateFiles();
            ensureWorkbook();
            refreshSnapshot(false);
        } catch (Exception e) {
            setConnection(false, "Connexion mémorisée inaccessible : " + e.getMessage());
        }
    }

    private Uri treeDocumentUri() {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
    }

    private void locateFiles() throws Exception {
        workbookUri = findChild(WORKBOOK_NAME);
        viewUri = findChild(VIEW_NAME);
        setConnection(true, "Dossier OneDrive connecté.");
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
        if (treeUri == null) {
            if (userMessage) toast("Connectez d'abord votre dossier OneDrive.");
            return;
        }
        try {
            locateFiles();
            ensureWorkbook();
            viewUri = findChild(VIEW_NAME);
            if (viewUri == null) {
                snapshot = null;
                syncStatus.setText("La vue lecture seule n'est pas encore disponible. Ouvrez Couple Finance sur le PC puis sauvegardez/actualisez la vue téléphone.");
                if (userMessage) toast("Fichier de vue non trouvé dans OneDrive.");
                showSection(currentSection);
                return;
            }
            String raw = readText(viewUri);
            snapshot = new JSONObject(raw);
            String gen = snapshot.optString("generatedAt", "—");
            String period = snapshot.optString("period", "—");
            syncStatus.setText("Vue PC : " + gen + "   •   " + period);
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
        connectionStatus.setText((ok ? "✓ " : "⚠ ") + msg);
        connectionStatus.setTextColor(ok ? Color.rgb(24,137,91) : Color.rgb(190,55,55));
    }

    private void showSection(String section) {
        currentSection = section;
        if (content == null) return;
        content.removeAllViews();
        TextView h = text(section, 20, true);
        h.setTextColor(Color.rgb(7,51,94));
        content.addView(h);

        if ("Saisie".equals(section)) {
            buildEntryForm();
            return;
        }
        TextView ro = text("Lecture seule — les données se modifient uniquement dans l'application PC.", 11, false);
        ro.setTextColor(Color.GRAY);
        content.addView(ro);
        spacer(content, 8);

        if (snapshot == null) {
            content.addView(infoCard("Aucune donnée", "Connectez OneDrive puis appuyez sur Actualiser. Le PC doit avoir créé " + VIEW_NAME + "."));
            return;
        }

        switch (section) {
            case "Accueil": renderHome(); break;
            case "Comptes": renderAccounts(); break;
            case "Revenus": renderIncome(); break;
            case "Dépenses": renderExpenses(); break;
            case "Transactions": renderTransactions(); break;
            case "Dettes": renderDebtsGoals(); break;
            case "Épargne": renderSavings(); break;
            case "Agenda": renderAgenda(); break;
            default: renderHome();
        }
    }

    private void renderHome() {
        JSONObject s = snapshot.optJSONObject("summary");
        if (s == null) s = new JSONObject();
        content.addView(kpiCard("Revenus reçus à date", money(s.optDouble("incomeReceived",0)), "À recevoir ce mois : " + money(s.optDouble("incomeExpected",0)), true));
        content.addView(kpiCard("Dépenses entrées à date", money(s.optDouble("expenseActual",0)), "Total prévu du mois : " + money(s.optDouble("expensePlanned",0)), false));
        content.addView(kpiCard("Situation des comptes", money(s.optDouble("accountsNet",0)), "Avoirs : " + money(s.optDouble("assets",0)) + " • Crédit/dettes comptes : " + money(s.optDouble("credit",0)), s.optDouble("accountsNet",0)>=0));
        content.addView(kpiCard("Dettes totales", money(s.optDouble("totalDebt",0)), "Objectifs financés / mois : " + money(s.optDouble("financedGoalsMonthly",0)), false));
        addCountSummary("Comptes", "accounts");
        addCountSummary("Transactions", "transactions");
        addCountSummary("Dettes", "debts");
        addCountSummary("Objectifs", "goals");
    }

    private void addCountSummary(String label, String key) {
        JSONArray a = snapshot.optJSONArray(key);
        content.addView(infoCard(label, (a == null ? 0 : a.length()) + " élément(s) dans la vue PC."));
    }

    private void renderAccounts() {
        JSONArray a = snapshot.optJSONArray("accounts");
        if (empty(a)) { emptyState("Aucun compte."); return; }
        for (int i=0;i<a.length();i++) {
            JSONObject x=a.optJSONObject(i); if(x==null)continue;
            double bal=x.optDouble("balance",0);
            String body=(x.optString("owner","Commun")+" • "+x.optString("institution","")+"\n"+
                    x.optString("type","Compte")+"\nSolde : "+money(bal));
            if(x.optDouble("limit",0)>0)body+="\nLimite : "+money(x.optDouble("limit",0))+" • Disponible : "+money(x.optDouble("available",0));
            if(x.optDouble("debt",0)>0)body+="\nDette liée : "+money(x.optDouble("debt",0));
            content.addView(infoCard(x.optString("name","Compte"),body));
        }
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
        JSONObject s=snapshot.optJSONObject("summary");
        if(s!=null)content.addView(kpiCard("Dépenses du mois",money(s.optDouble("expenseActual",0)),"Total prévu : "+money(s.optDouble("expensePlanned",0)),false));
        content.addView(sectionLabel("Budget"));
        renderArray("budgets", 60, x ->
                x.optString("cat","Catégorie"),
                x -> "Réel : "+money(x.optDouble("spent",0))+" • Prévu : "+money(x.optDouble("budget",0)));
        content.addView(sectionLabel("Dépenses constantes"));
        renderArray("recurring", 60, x ->
                x.optString("name","Dépense constante"),
                x -> x.optString("owner","Commun")+" • "+x.optString("cat","Autres")+"\n"+money(x.optDouble("amount",0))+" • Jour "+x.optInt("day",1)+" • "+(x.optBoolean("active",true)?"Active":"Inactive"));
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
        content.addView(sectionLabel("Dettes"));
        renderArray("debts",80,x ->
                x.optString("owner","Commun")+" • "+x.optString("name","Dette"),
                x -> "Solde : "+money(x.optDouble("balance",0))+" • Taux : "+fmt(x.optDouble("rate",0))+" %\nMinimum : "+money(x.optDouble("min",0))+" • Échéance : "+x.optString("dueDate","—"));
        content.addView(sectionLabel("Objectifs"));
        renderArray("goals",80,x ->
                x.optString("name","Objectif"),
                x -> "Objectif : "+money(x.optDouble("price",0))+" • Épargné : "+money(x.optDouble("saved",0))+"\nMode : "+x.optString("mode",""));
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
        TextView info=text("Seule cette page écrit des données. Les autres pages sont strictement en lecture seule.",11,false);
        info.setTextColor(Color.GRAY);content.addView(info);spacer(content,8);

        entryType=new Spinner(this);
        entryType.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Dépense","Revenu","Transaction","Épargne","Événement"}));
        addLabeled(content,"Type de saisie",entryType);

        owner=new Spinner(this);
        owner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Homme","Femme","Commun"}));
        owner.setSelection(2); addLabeled(content,"Personne",owner);

        amount=input("0.00");amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);addLabeled(content,"Montant ($)",amount);
        date=input(isoDate());date.setFocusable(false);date.setOnClickListener(v->pickDate());addLabeled(content,"Date",date);
        time=input(hmTime());time.setFocusable(false);time.setOnClickListener(v->pickTime());addLabeled(content,"Heure",time);

        category=new Spinner(this);
        category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,categoryLabels()));
        addLabeled(content,"Catégorie",category);

        description=input("Ex. IGA, salaire, rendez-vous...");addLabeled(content,"Description",description);

        sourceAccount=new Spinner(this);
        sourceAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accountLabels(true)));
        addLabeled(content,"Compte / prélevé sur / déposé dans",sourceAccount);

        destinationBlock=vertical();
        destinationAccount=new Spinner(this);
        destinationAccount.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accountLabels(false)));
        addLabeled(destinationBlock,"Compte destination (Épargne)",destinationAccount);
        content.addView(destinationBlock);
        destinationBlock.setVisibility(View.GONE);

        note=input("Optionnel");note.setMinLines(3);note.setSingleLine(false);note.setGravity(Gravity.TOP);addLabeled(content,"Note",note);

        entryType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){destinationBlock.setVisibility("Épargne".equals(String.valueOf(entryType.getSelectedItem()))?View.VISIBLE:View.GONE);}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });

        saveButton=button("Enregistrer dans Couple Finance");
        saveButton.setTextColor(Color.WHITE);saveButton.setBackgroundColor(Color.rgb(24,137,91));saveButton.setTextSize(16);saveButton.setPadding(dp(10),dp(14),dp(10),dp(14));
        saveButton.setOnClickListener(v->saveEntry());
        content.addView(saveButton,new LinearLayout.LayoutParams(-1,-2));
    }

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

    private void saveEntry() {
        if(treeUri==null){toast("Connectez d'abord le dossier OneDrive.");return;}
        try{locateFiles();ensureWorkbook();}catch(Exception e){toast("Excel mobile inaccessible : "+e.getMessage());return;}
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
        }else e.note=userNote;

        saveButton.setEnabled(false);
        new Thread(()->{
            try{
                XlsxAppender.append(getContentResolver(),workbookUri,e);
                runOnUiThread(()->{
                    saveButton.setEnabled(true);amount.setText("");description.setText("");note.setText("");time.setText(hmTime());
                    toast("Enregistré dans OneDrive. Couple Finance PC l'importera.");
                });
            }catch(Exception ex){
                runOnUiThread(()->{saveButton.setEnabled(true);toast("Erreur d'écriture : "+ex.getMessage());});
            }
        }).start();
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
        GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(10));g.setStroke(dp(1),Color.rgb(218,229,239));c.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));c.setLayoutParams(p);return c;
    }
    private Button smallNavButton(String s){Button b=button(s);b.setTextSize(12);b.setPadding(dp(10),dp(5),dp(10),dp(5));return b;}
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