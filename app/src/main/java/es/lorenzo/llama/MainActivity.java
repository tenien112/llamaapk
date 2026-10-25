package es.lorenzo.llama;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLAMA-LORENZITO · by Lorenzo
 * Dices «llama a Fulanico» y llama directamente, con todos los contactos del móvil
 * y desde la SIM elegida (por defecto la del 609 807 269).
 */
public class MainActivity extends Activity {

    private static final String NUMERO_PREFERIDO = "609807269";
    private static final int VERDE = Color.parseColor("#1FA855");
    private static final int ROJO = Color.parseColor("#D9473F");
    private static final int GRIS = Color.parseColor("#6B7770");
    private static final int FONDO = Color.parseColor("#F4F7F5");

    static class Contacto {
        String nombre;
        List<String> tels = new ArrayList<>();
        boolean conPrincipal = false;
    }

    static class Resultado {
        Contacto c;
        double p;
        Resultado(Contacto c, double p) { this.c = c; this.p = p; }
    }

    private final List<Contacto> contactos = new ArrayList<>();
    private SharedPreferences prefs;
    private SpeechRecognizer rec;
    private boolean escuchando = false;
    private boolean escucharAlAbrir = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable llamadaPendiente;

    private TextView estado, oido, infoSim, infoContactos;
    private Button mic;
    private LinearLayout opciones;

    // ---------------------------------------------------------------- interfaz

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable fondoRedondo(int color, float radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radio));
        return g;
    }

    private Button boton(String texto, int color, int colorTexto, float tam) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setTextSize(tam);
        b.setTextColor(colorTexto);
        b.setBackground(fondoRedondo(color, 16));
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("llama", MODE_PRIVATE);
        getWindow().setStatusBarColor(VERDE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(FONDO);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setGravity(Gravity.CENTER_HORIZONTAL);
        raiz.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(raiz);

        TextView titulo = new TextView(this);
        titulo.setText("📞 LLAMA-LORENZITO");
        titulo.setTextSize(20);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        titulo.setTextColor(Color.parseColor("#1B2420"));
        raiz.addView(titulo);

        TextView sub = new TextView(this);
        sub.setText("Di «llama a…» y el nombre");
        sub.setTextColor(GRIS);
        raiz.addView(sub);

        mic = new Button(this);
        mic.setText("🎤");
        mic.setTextSize(64);
        mic.setTextColor(Color.WHITE);
        GradientDrawable circulo = new GradientDrawable();
        circulo.setShape(GradientDrawable.OVAL);
        circulo.setColor(VERDE);
        mic.setBackground(circulo);
        LinearLayout.LayoutParams lpMic = new LinearLayout.LayoutParams(dp(190), dp(190));
        lpMic.topMargin = dp(36);
        lpMic.bottomMargin = dp(16);
        mic.setLayoutParams(lpMic);
        mic.setOnClickListener(v -> { if (escuchando) pararEscucha(); else escucha(); });
        raiz.addView(mic);

        estado = new TextView(this);
        estado.setTextSize(22);
        estado.setTypeface(Typeface.DEFAULT_BOLD);
        estado.setGravity(Gravity.CENTER);
        estado.setTextColor(Color.parseColor("#1B2420"));
        estado.setText("Pulsa y di «llama a…»");
        raiz.addView(estado);

        oido = new TextView(this);
        oido.setTextSize(16);
        oido.setGravity(Gravity.CENTER);
        oido.setTextColor(GRIS);
        oido.setPadding(0, dp(4), 0, dp(8));
        raiz.addView(oido);

        opciones = new LinearLayout(this);
        opciones.setOrientation(LinearLayout.VERTICAL);
        raiz.addView(opciones, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Escribir en vez de hablar
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpFila = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpFila.topMargin = dp(18);
        fila.setLayoutParams(lpFila);
        final EditText texto = new EditText(this);
        texto.setHint("O escríbelo: llama a…");
        texto.setSingleLine(true);
        texto.setInputType(InputType.TYPE_CLASS_TEXT);
        texto.setImeOptions(EditorInfo.IME_ACTION_GO);
        texto.setBackground(fondoRedondo(Color.WHITE, 12));
        texto.setPadding(dp(12), dp(12), dp(12), dp(12));
        fila.addView(texto, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button ir = boton("Llamar", VERDE, Color.WHITE, 16);
        LinearLayout.LayoutParams lpIr = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lpIr.leftMargin = dp(8);
        ir.setLayoutParams(lpIr);
        View.OnClickListener enviar = v -> {
            String t = texto.getText().toString().trim();
            if (!t.isEmpty()) { texto.setText(""); procesa(t); }
        };
        ir.setOnClickListener(enviar);
        texto.setOnEditorActionListener((v, id, ev) -> { enviar.onClick(v); return true; });
        fila.addView(ir);
        raiz.addView(fila);

        infoContactos = new TextView(this);
        infoContactos.setTextColor(GRIS);
        infoContactos.setGravity(Gravity.CENTER);
        infoContactos.setPadding(0, dp(18), 0, 0);
        raiz.addView(infoContactos);

        infoSim = new TextView(this);
        infoSim.setTextColor(VERDE);
        infoSim.setGravity(Gravity.CENTER);
        infoSim.setPadding(0, dp(6), 0, dp(6));
        infoSim.setOnClickListener(v -> eligeSim(null));
        raiz.addView(infoSim);

        TextView pie = new TextView(this);
        pie.setText("LLAMA-LORENZITO · by Lorenzo\n© " + Calendar.getInstance().get(Calendar.YEAR)
                + " Lorenzo Moreno Villalba");
        pie.setTextSize(12);
        pie.setTextColor(GRIS);
        pie.setGravity(Gravity.CENTER);
        pie.setPadding(0, dp(24), 0, 0);
        raiz.addView(pie);

        setContentView(scroll);
        avisoActualizacion();
        pidePermisos();
    }

    private void avisoActualizacion() {
        try {
            long v = Build.VERSION.SDK_INT >= 28
                    ? getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode()
                    : getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            long antes = prefs.getLong("version", -1);
            prefs.edit().putLong("version", v).apply();
            if (antes != -1 && antes != v) {
                Toast.makeText(this, "LLAMA-LORENZITO se ha actualizado", Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- permisos

    private boolean tiene(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private void pidePermisos() {
        List<String> faltan = new ArrayList<>();
        String[] todos = Build.VERSION.SDK_INT >= 26
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS}
                : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE};
        for (String p : todos) if (!tiene(p)) faltan.add(p);
        if (faltan.isEmpty()) listo();
        else requestPermissions(faltan.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        listo();
    }

    private void listo() {
        cargaContactos();
        pintaSim();
        if (escucharAlAbrir) {
            escucharAlAbrir = false;
            handler.postDelayed(this::escucha, 400);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Al volver a abrirla desde el icono, se pone a escuchar directamente
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            limpia();
            handler.postDelayed(this::escucha, 400);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pararEscucha();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rec != null) rec.destroy();
    }

    // ---------------------------------------------------------------- contactos

    private void cargaContactos() {
        contactos.clear();
        if (!tiene(Manifest.permission.READ_CONTACTS)) {
            infoContactos.setText("Sin permiso para leer los contactos. Toca aquí para darlo.");
            infoContactos.setOnClickListener(v -> abreAjustesApp());
            return;
        }
        Map<String, Contacto> mapa = new LinkedHashMap<>();
        String[] cols = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY};
        try (Cursor cur = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                cols, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    String nombre = cur.getString(0);
                    String num = cur.getString(1);
                    if (nombre == null || num == null) continue;
                    int tipo = cur.getInt(2);
                    boolean principal = cur.getInt(3) != 0;
                    String limpio = num.replaceAll("[^0-9+]", "");
                    if (limpio.length() < 3) continue;
                    String clave = normaliza(nombre);
                    Contacto c = mapa.get(clave);
                    if (c == null) { c = new Contacto(); c.nombre = nombre; mapa.put(clave, c); }
                    if (yaEsta(c.tels, limpio)) continue;
                    boolean movil = tipo == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE;
                    // Primero el número principal; si no hay, el móvil
                    if (principal) { c.tels.add(0, limpio); c.conPrincipal = true; }
                    else if (movil && !c.conPrincipal) c.tels.add(0, limpio);
                    else c.tels.add(limpio);
                }
            }
        } catch (Exception e) {
            infoContactos.setText("No se pudieron leer los contactos");
            return;
        }
        contactos.addAll(mapa.values());
        infoContactos.setText(contactos.size() + " contactos del móvil");
        infoContactos.setOnClickListener(v -> { cargaContactos(); Toast.makeText(this, "Contactos actualizados", Toast.LENGTH_SHORT).show(); });
    }

    private boolean yaEsta(List<String> tels, String n) {
        String a = ultimos9(n);
        for (String t : tels) if (ultimos9(t).equals(a)) return true;
        return false;
    }

    private static String ultimos9(String n) {
        String d = n.replaceAll("[^0-9]", "");
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    private void abreAjustesApp() {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(i);
    }

    // ---------------------------------------------------------------- búsqueda por nombre

    static String normaliza(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.toLowerCase(new Locale("es", "ES")), Normalizer.Form.NFD);
        t = t.replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    static String sacaNombre(String frase) {
        String t = normaliza(frase);
        t = t.replaceAll("^(oye |vale |venga |por favor )+", "");
        t = t.replaceAll("^(puedes |quiero )?(llama(me|r|le)?|llamada|marca(r)?|telefonea(r)?|telefono|ponme con|pasame con|habla(r)? con|contacta(r)? con)( a| al| con| de)?\\s*", "");
        t = t.replaceAll("\\s*(por favor|ahora|ya)$", "");
        t = t.replaceAll("^(el|la|los|las|mi|a)\\s+", "");
        return t.trim();
    }

    static int lev(String a, String b) {
        int m = a.length(), n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            int[] cur = new int[n + 1];
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int coste = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + coste);
            }
            prev = cur;
        }
        return prev[n];
    }

    static double parecido(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1;
        return 1.0 - (double) lev(a, b) / max;
    }

    static double puntua(String dicho, String nombre) {
        String n = normaliza(nombre);
        if (n.isEmpty() || dicho.isEmpty()) return 0;
        if (n.equals(dicho)) return 1;
        String[] pn = n.split(" ");
        String[] pd = dicho.split(" ");
        if (n.startsWith(dicho + " ") || pn[0].equals(dicho)) return 0.93;
        boolean todas = true;
        for (String w : pd) {
            boolean esta = false;
            for (String x : pn) if (x.equals(w)) { esta = true; break; }
            if (!esta) { todas = false; break; }
        }
        if (todas) return 0.9;
        boolean prefijos = true;
        for (String w : pd) {
            boolean esta = false;
            for (String x : pn) if (w.length() >= 3 && x.startsWith(w)) { esta = true; break; }
            if (!esta) { prefijos = false; break; }
        }
        if (prefijos) return 0.82;
        double suma = 0;
        for (String w : pd) {
            double mejor = 0;
            for (String x : pn) mejor = Math.max(mejor, parecido(w, x));
            suma += mejor;
        }
        double porPalabra = suma / pd.length;
        return Math.max(porPalabra * 0.85, parecido(dicho, n) * 0.85);
    }

    private List<Resultado> busca(String dicho) {
        List<Resultado> res = new ArrayList<>();
        if (dicho.isEmpty()) return res;
        for (Contacto c : contactos) {
            double p = puntua(dicho, c.nombre);
            if (p >= 0.6) res.add(new Resultado(c, p));
        }
        Collections.sort(res, (x, y) -> Double.compare(y.p, x.p));
        return res;
    }

    // ---------------------------------------------------------------- voz

    private void escucha() {
        if (escuchando) return;
        if (!tiene(Manifest.permission.RECORD_AUDIO)) {
            estado.setText("Dale permiso al micrófono");
            pidePermisos();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            estado.setText("Este móvil no tiene reconocimiento de voz. Escríbelo abajo.");
            return;
        }
        limpia();
        if (rec != null) rec.destroy();
        rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                escuchando = true;
                pintaMic(true);
                estado.setText("Te escucho…");
                oido.setText("");
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                escuchando = false;
                pintaMic(false);
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    estado.setText("No te he oído. Pulsa el micro");
                else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    estado.setText("Dale permiso al micrófono");
                else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
                    estado.setText("Sin conexión para entender la voz");
                else if (error != SpeechRecognizer.ERROR_CLIENT)
                    estado.setText("No te he entendido. Pulsa el micro");
            }
            @Override public void onResults(Bundle results) {
                escuchando = false;
                pintaMic(false);
                ArrayList<String> alts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (alts == null || alts.isEmpty()) { estado.setText("No te he entendido"); return; }
                // De todas las versiones que entiende el dictado, la que mejor encaja con un contacto
                String mejor = alts.get(0);
                double mejorP = -1;
                for (String a : alts) {
                    List<Resultado> r = busca(sacaNombre(a));
                    double p = r.isEmpty() ? 0 : r.get(0).p;
                    if (p > mejorP + 0.05) { mejorP = p; mejor = a; }
                }
                procesa(mejor);
            }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> p = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) oido.setText("«" + p.get(0) + "»");
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try { rec.startListening(i); } catch (Exception e) { estado.setText("No se pudo usar el micrófono"); }
    }

    private void pararEscucha() {
        if (rec != null && escuchando) { try { rec.stopListening(); } catch (Exception ignored) { } }
        escuchando = false;
        pintaMic(false);
    }

    private void pintaMic(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? ROJO : VERDE);
        mic.setBackground(g);
    }

    // ---------------------------------------------------------------- llamar

    private void limpia() {
        opciones.removeAllViews();
        if (llamadaPendiente != null) { handler.removeCallbacks(llamadaPendiente); llamadaPendiente = null; }
    }

    private void procesa(String frase) {
        limpia();
        oido.setText("«" + frase + "»");
        String soloNum = frase.replaceAll("[^0-9+]", "");
        if (soloNum.length() >= 9 && soloNum.length() >= frase.replaceAll("\\s", "").length() * 0.6) {
            Contacto c = new Contacto(); c.nombre = soloNum; c.tels.add(soloNum);
            prepara(c);
            return;
        }
        if (contactos.isEmpty()) { estado.setText("No veo tus contactos. Dale permiso."); pidePermisos(); return; }
        String dicho = sacaNombre(frase);
        if (dicho.isEmpty()) { estado.setText("¿A quién llamo?"); return; }
        List<Resultado> r = busca(dicho);
        if (r.isEmpty()) { estado.setText("No encuentro a «" + dicho + "»"); return; }
        if (r.size() == 1 || r.get(0).p - r.get(1).p >= 0.08) { prepara(r.get(0).c); return; }
        estado.setText("¿Cuál de ellos?");
        for (int k = 0; k < Math.min(6, r.size()); k++) {
            final Contacto c = r.get(k).c;
            Button b = boton(c.nombre + "   " + c.tels.get(0), Color.WHITE, Color.parseColor("#1B2420"), 17);
            b.setOnClickListener(v -> prepara(c));
            opciones.addView(b);
        }
    }

    private void prepara(final Contacto c) {
        limpia();
        if (c.tels.size() > 1) {
            estado.setText("¿A qué número de " + c.nombre + "?");
            for (final String t : c.tels) {
                Button b = boton(t, Color.WHITE, Color.parseColor("#1B2420"), 18);
                b.setOnClickListener(v -> llamar(c, t));
                opciones.addView(b);
            }
            return;
        }
        // Llama ya; un segundo para poder cancelar si ha entendido otro nombre
        estado.setText("📞 Llamando a " + c.nombre);
        Button cancelar = boton("Cancelar", ROJO, Color.WHITE, 18);
        cancelar.setOnClickListener(v -> { limpia(); estado.setText("Cancelado"); });
        opciones.addView(cancelar);
        llamadaPendiente = () -> llamar(c, c.tels.get(0));
        handler.postDelayed(llamadaPendiente, 1000);
    }

    private void llamar(Contacto c, String tel) {
        limpia();
        if (!tiene(Manifest.permission.CALL_PHONE)) {
            estado.setText("Falta el permiso de llamadas");
            pidePermisos();
            return;
        }
        PhoneAccountHandle sim = simElegida();
        if (sim == null && necesitaElegirSim()) {
            eligeSim(() -> llamar(c, tel));
            return;
        }
        estado.setText("📞 Llamando a " + c.nombre);
        Intent i = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", tel, null));
        if (sim != null) i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim);
        try {
            startActivity(i);
        } catch (Exception e) {
            estado.setText("No se pudo llamar");
        }
    }

    // ---------------------------------------------------------------- SIM

    private List<PhoneAccountHandle> cuentas() {
        List<PhoneAccountHandle> l = new ArrayList<>();
        try {
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tiene(Manifest.permission.READ_PHONE_STATE)) l.addAll(tm.getCallCapablePhoneAccounts());
        } catch (Exception ignored) { }
        return l;
    }

    private boolean necesitaElegirSim() { return cuentas().size() > 1; }

    private String etiqueta(PhoneAccountHandle h) {
        try {
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            PhoneAccount a = tm.getPhoneAccount(h);
            String txt = a != null && a.getLabel() != null ? a.getLabel().toString() : "SIM";
            String num = numeroDe(h);
            if (num != null && !num.isEmpty()) txt += " · " + num;
            return txt;
        } catch (Exception e) {
            return "SIM";
        }
    }

    /** Número de la línea de una cuenta (si Android lo deja ver). */
    private String numeroDe(PhoneAccountHandle h) {
        try {
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            PhoneAccount a = tm.getPhoneAccount(h);
            if (a != null && a.getAddress() != null) {
                String s = a.getAddress().getSchemeSpecificPart();
                if (s != null && s.replaceAll("[^0-9]", "").length() >= 9) return s;
            }
            SubscriptionInfo info = subDe(h);
            if (info != null) {
                String n = null;
                SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (Build.VERSION.SDK_INT >= 33 && tiene(Manifest.permission.READ_PHONE_NUMBERS)) {
                    try { n = sm.getPhoneNumber(info.getSubscriptionId()); } catch (Exception ignored) { }
                }
                if (n == null || n.isEmpty()) n = info.getNumber();
                return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private SubscriptionInfo subDe(PhoneAccountHandle h) {
        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null) return null;
            String id = h.getId();
            for (SubscriptionInfo s : subs) {
                if (id.equals(String.valueOf(s.getSubscriptionId()))) return s;
                String icc = s.getIccId();
                if (icc != null && !icc.isEmpty() && (id.startsWith(icc) || icc.startsWith(id))) return s;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private PhoneAccountHandle simElegida() {
        List<PhoneAccountHandle> l = cuentas();
        if (l.isEmpty()) return null;
        String guardada = prefs.getString("sim", null);
        if (guardada != null) for (PhoneAccountHandle h : l) if (h.getId().equals(guardada)) return h;
        // Busca sola la SIM del 609 807 269
        for (PhoneAccountHandle h : l) {
            String n = numeroDe(h);
            if (n != null && ultimos9(n).equals(NUMERO_PREFERIDO)) {
                prefs.edit().putString("sim", h.getId()).apply();
                return h;
            }
        }
        if (l.size() == 1) return l.get(0);
        return null;
    }

    private void pintaSim() {
        PhoneAccountHandle h = simElegida();
        if (h != null && cuentas().size() > 1) infoSim.setText("Llama con: " + etiqueta(h) + "  (cambiar)");
        else if (h == null && cuentas().size() > 1) infoSim.setText("Toca aquí para elegir la SIM con la que llamar");
        else infoSim.setText("");
    }

    private void eligeSim(final Runnable despues) {
        final List<PhoneAccountHandle> l = cuentas();
        if (l.size() < 2) { if (despues != null) despues.run(); return; }
        String[] nombres = new String[l.size()];
        for (int k = 0; k < l.size(); k++) nombres[k] = etiqueta(l.get(k));
        new AlertDialog.Builder(this)
                .setTitle("¿Con qué SIM llamo siempre?")
                .setItems(nombres, (d, which) -> {
                    prefs.edit().putString("sim", l.get(which).getId()).apply();
                    pintaSim();
                    if (despues != null) despues.run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
