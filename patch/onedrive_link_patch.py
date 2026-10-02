from pathlib import Path
import re
p=Path("extracted/CoupleFinance_Mobile_Android_V2_VIEWER/app/src/main/java/com/couplefinance/mobile/MainActivity.java")
text=p.read_text(encoding="utf-8")

text=text.replace('    private static final int PICK_FOLDER = 3001;\n',
                  '    private static final int PICK_FOLDER = 3001;\n    private static final int PICK_WORKBOOK = 3002;\n    private static final int PICK_VIEW = 3003;\n',1)
text=text.replace('    private static final String PREF_TREE = "onedrive_tree_uri";\n',
                  '    private static final String PREF_TREE = "onedrive_tree_uri";\n    private static final String PREF_WORKBOOK = "onedrive_workbook_uri";\n    private static final String PREF_VIEW = "onedrive_view_uri";\n    private static final String PREF_LINK = "onedrive_folder_link";\n',1)
text=text.replace('    private TextView syncStatus;\n',
                  '    private TextView syncStatus;\n    private EditText oneDriveLink;\n',1)

text=text.replace("""            if (treeUri != null) {
                try { refreshSnapshot(false); } catch (Exception ignored) {}
            }""","""            if (treeUri != null || viewUri != null) {
                try { refreshSnapshot(false); } catch (Exception ignored) {}
            }""",1)
text=text.replace("""        if (treeUri != null) {
            try { refreshSnapshot(false); } catch (Exception ignored) {}
        }""","""        if (treeUri != null || viewUri != null) {
            try { refreshSnapshot(false); } catch (Exception ignored) {}
        }""",1)

old = """        LinearLayout connectRow = horizontal();
        Button connect = button("Connecter OneDrive");
        connect.setOnClickListener(v -> chooseFolder());
        connectRow.addView(connect, weight());
        Button refresh = button("Actualiser");
        refresh.setOnClickListener(v -> refreshSnapshot(true));
        connectRow.addView(refresh, weight());
        root.addView(connectRow);

        connectionStatus = text("Aucun dossier connecté.", 12, false);
"""
new = """        TextView linkLabel = text("Lien du dossier OneDrive (optionnel)", 12, true);
        root.addView(linkLabel);
        oneDriveLink = input("https://1drv.ms/... ou https://onedrive.live.com/...");
        oneDriveLink.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LINK, ""));
        root.addView(oneDriveLink, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout linkRow = horizontal();
        Button saveLink = button("Mémoriser le lien");
        saveLink.setOnClickListener(v -> saveOneDriveLink());
        linkRow.addView(saveLink, weight());
        Button openLink = button("Ouvrir OneDrive");
        openLink.setOnClickListener(v -> openOneDriveLink());
        linkRow.addView(openLink, weight());
        root.addView(linkRow);

        LinearLayout connectRow = horizontal();
        Button connect = button("Choisir dossier");
        connect.setOnClickListener(v -> chooseFolder());
        connectRow.addView(connect, weight());
        Button refresh = button("Actualiser");
        refresh.setOnClickListener(v -> refreshSnapshot(true));
        connectRow.addView(refresh, weight());
        root.addView(connectRow);

        LinearLayout fileRow = horizontal();
        Button chooseWorkbook = button("Choisir Excel OneDrive");
        chooseWorkbook.setOnClickListener(v -> chooseWorkbookFile());
        fileRow.addView(chooseWorkbook, weight());
        Button chooseView = button("Choisir vue PC JSON");
        chooseView.setOnClickListener(v -> chooseViewFile());
        fileRow.addView(chooseView, weight());
        root.addView(fileRow);

        TextView help = text("Si OneDrive n'apparaît pas dans « Choisir dossier », utilise le lien ci-dessus pour ouvrir OneDrive puis sélectionne les 2 fichiers individuellement. Cette méthode fonctionne mieux avec les fournisseurs cloud Android.", 11, false);
        help.setTextColor(Color.GRAY);
        help.setPadding(0, dp(4), 0, dp(4));
        root.addView(help);

        connectionStatus = text("Aucune source OneDrive connectée.", 12, false);
"""
if old not in text: raise SystemExit("connection block not found")
text=text.replace(old,new,1)

anchor='    private void chooseFolder() {\n'
methods = """    private void saveOneDriveLink() {
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

"""
text=text.replace(anchor,methods+anchor,1)

pat=re.compile(r'    @Override protected void onActivityResult\(int req, int result, Intent data\) \{.*?\n    \}\n\n    private void restoreFolder\(\)',re.S)
replacement="""    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}

        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();

        if (req == PICK_FOLDER) {
            treeUri = uri;
            ed.putString(PREF_TREE, uri.toString()).apply();
            try {
                locateFiles();
                ensureWorkbook();
                refreshSnapshot(true);
            } catch (Exception e) {
                setConnection(false, "Erreur OneDrive : " + e.getMessage());
            }
            return;
        }

        if (req == PICK_WORKBOOK) {
            workbookUri = uri;
            ed.putString(PREF_WORKBOOK, uri.toString()).apply();
            setConnection(true, "Excel OneDrive sélectionné.");
            toast("Fichier Excel mémorisé.");
            return;
        }

        if (req == PICK_VIEW) {
            viewUri = uri;
            ed.putString(PREF_VIEW, uri.toString()).apply();
            setConnection(true, "Vue PC OneDrive sélectionnée.");
            refreshSnapshot(true);
        }
    }

    private void restoreFolder()"""
text,n=pat.subn(replacement,text,count=1)
if n!=1: raise SystemExit("onActivityResult replacement failed")

pat=re.compile(r'    private void restoreFolder\(\) \{.*?\n    \}\n\n    private Uri treeDocumentUri\(\)',re.S)
replacement="""    private void restoreFolder() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String t = sp.getString(PREF_TREE, "");
        String w = sp.getString(PREF_WORKBOOK, "");
        String v = sp.getString(PREF_VIEW, "");
        String link = sp.getString(PREF_LINK, "");

        if (oneDriveLink != null) oneDriveLink.setText(link);
        if (!t.isEmpty()) treeUri = Uri.parse(t);
        if (!w.isEmpty()) workbookUri = Uri.parse(w);
        if (!v.isEmpty()) viewUri = Uri.parse(v);

        try {
            if (treeUri != null) locateFiles();
            if (treeUri != null && workbookUri == null) ensureWorkbook();
            if (viewUri != null || treeUri != null) refreshSnapshot(false);
            else if (workbookUri != null) setConnection(true, "Excel OneDrive mémorisé. Choisis maintenant la vue PC JSON.");
        } catch (Exception e) {
            setConnection(false, "Connexion mémorisée inaccessible : " + e.getMessage());
        }
    }

    private Uri treeDocumentUri()"""
text,n=pat.subn(replacement,text,count=1)
if n!=1: raise SystemExit("restore replacement failed")

text=text.replace("""    private void locateFiles() throws Exception {
        workbookUri = findChild(WORKBOOK_NAME);
        viewUri = findChild(VIEW_NAME);
        setConnection(true, "Dossier OneDrive connecté.");
    }""","""    private void locateFiles() throws Exception {
        if (treeUri == null) return;
        Uri wb = findChild(WORKBOOK_NAME);
        Uri vw = findChild(VIEW_NAME);
        if (wb != null) workbookUri = wb;
        if (vw != null) viewUri = vw;
        setConnection(true, "Dossier OneDrive connecté.");
    }""",1)

text=text.replace("""        if (treeUri == null) {
            if (userMessage) toast("Connectez d'abord votre dossier OneDrive.");
            return;
        }
        try {
            locateFiles();
            ensureWorkbook();
            viewUri = findChild(VIEW_NAME);
            if (viewUri == null) {""","""        if (treeUri == null && viewUri == null) {
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
            if (viewUri == null) {""",1)

text=text.replace("""        if(treeUri==null){toast("Connectez d'abord le dossier OneDrive.");return;}
        try{locateFiles();ensureWorkbook();}catch(Exception e){toast("Excel mobile inaccessible : "+e.getMessage());return;}""","""        if(treeUri==null && workbookUri==null){toast("Choisis le dossier OneDrive ou le fichier Excel OneDrive.");return;}
        try{
            if(treeUri!=null){locateFiles();if(workbookUri==null)ensureWorkbook();}
            if(workbookUri==null)throw new Exception("CoupleFinance_Mobile.xlsx n'est pas sélectionné.");
        }catch(Exception e){toast("Excel mobile inaccessible : "+e.getMessage());return;}""",1)

text=text.replace('TextView title = text("♥ Couple Finance Mobile", 24, true);',
                  'TextView title = text("♥ Couple Finance Mobile 2.1", 24, true);',1)

p.write_text(text,encoding="utf-8")
