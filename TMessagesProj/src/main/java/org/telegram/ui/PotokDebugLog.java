package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Временный диагностический лог для двух конкретных багов, которые не удалось
 * закрыть чисто по чтению кода: (1) непостоянный блюр на некоторых постах с фото,
 * (2) двоение кадров видео/GIF в ленте после возврата из полноэкранного режима.
 *
 * Не пишет в файл (чтобы не плодить лишние permissions/IO на телефоне пользователя) —
 * держит последние 400 строк в памяти (кольцевой буфер) + дублирует в logcat через
 * FileLog.d(), и отдаётся текстом через getAll() для показа в UI (см. вкладку
 * "Контакты" — ContactsActivity, длинное нажатие на заголовок; либо вкладка
 * "Контакты" в нижних табах — MainTabsActivity, длинное нажатие на саму вкладку).
 *
 * КАК УБРАТЬ ПОСЛЕ ДИАГНОСТИКИ: это временный код, не часть финального продукта.
 * Как только причины блюра/двоения кадров найдены и исправлены, все вызовы
 * PotokDebugLog.*(...) и сам этот класс можно смело удалить.
 */
public class PotokDebugLog {

    // ⚠️ ТОЧЕЧНАЯ ДИАГНОСТИКА ОДНОГО КОНКРЕТНОГО ПОСТА (по прямому требованию
    // пользователя): id сообщения, для которого во всех местах ниже (см. тег
    // TARGETPOST в PotokFeedPostCell.java / ImageReceiver.java / MediaController.java /
    // ChatMessageCell.java) идёт БЕЗУСЛОВНАЯ запись на каждый реальный вызов —
    // без гейтов "если изменилось". Поменять здесь id — и точечное логирование
    // сразу переключится на другой пост, без правок в остальных файлах.
    public static volatile long TARGET_MESSAGE_ID = 968L;

    private static final int MAX_LINES = 4000;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Файл "хвоста" лога для диагностики ЗАВИСАНИЙ (не крашей — на них есть отдельный
    // CRASH_FILE_NAME/installCrashHandler). При настоящем зависании (бесконечный цикл/deadlock
    // на главном потоке, без исключения) пользователю придётся убить процесс силой — весь
    // in-memory буфер lines пропадёт вместе с ним, а экран логов (диалог) открыть будет
    // невозможно, т.к. он тоже требует главный поток. Поэтому здесь же, синхронно и на каждую
    // строку (не только на краш), дублируем в файл — переживает kill -9. При следующем запуске
    // содержимое подставляется в начало буфера (см. loadPendingCrashIfAny), как и для крашей.
    private static final String HANG_FILE_NAME = "potok_last_session_log_tail.txt";
    private static final int HANG_FILE_MAX_LINES = 300;
    private static Context hangFileContext;
    private static final ArrayDeque<String> hangFileTail = new ArrayDeque<>();

    private static boolean crashHandlerInstalled = false;

    public static synchronized void d(String tag, String message) {
        String line = fmt.format(new Date()) + " [" + tag + "] " + message;
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        FileLog.d("PotokDebug: " + line);
        appendToHangFile(line);
    }

    // Держим на диске только "хвост" (последние HANG_FILE_MAX_LINES строк) — интересующая нас
    // при зависании информация всегда в конце, а перезаписывать весь файл целиком на каждую
    // строку (чтобы не разрастался бесконечно) дешевле, чем построчный append без ограничения.
    private static void appendToHangFile(String line) {
        if (hangFileContext == null) {
            return;
        }
        hangFileTail.addLast(line);
        while (hangFileTail.size() > HANG_FILE_MAX_LINES) {
            hangFileTail.removeFirst();
        }
        try (java.io.FileOutputStream fos = hangFileContext.openFileOutput(HANG_FILE_NAME, Context.MODE_PRIVATE)) {
            StringBuilder sb = new StringBuilder();
            for (String l : hangFileTail) {
                sb.append(l).append('\n');
            }
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } catch (Throwable ignore) {
            // диагностика не должна мешать работе приложения
        }
    }

    /**
     * Алиас d() — часть вызовов в проекте исторически писалась через log(),
     * часть через d(); оставлены оба имени, чтобы не переписывать call-сайты.
     */
    public static void log(String tag, String message) {
        d(tag, message);
    }

    public static synchronized String getAll() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("(логов пока нет — сначала воспроизведи баг: полистай ленту с фото/видео, "
                + "открой видео на весь экран и вернись назад, затем открой этот экран снова)");
        }
        return sb.toString();
    }

    /**
     * Те же строки, что getAll(), но оставлены только те, что содержат filter
     * (без учёта регистра) — либо в теге, либо в тексте сообщения.
     * Если filter пустой/null — ведёт себя как getAll().
     */
    public static synchronized String getFiltered(String filter) {
        if (filter == null || filter.isEmpty()) {
            return getAll();
        }
        String needle = filter.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.toLowerCase(Locale.US).contains(needle)) {
                sb.append(line).append('\n');
            }
        }
        if (sb.length() == 0) {
            sb.append("(нет строк, содержащих \"").append(filter).append("\" — всего в буфере ")
                .append(lines.size()).append(" строк(и); открой без фильтра, если нужно всё)");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        lines.clear();
    }

    /**
     * Показывает диалог с логом, отфильтрованным по filter (см. getFiltered()).
     * Копия диалога из ContactsActivity.showPotokDebugLogDialog(), но переиспользуемая
     * из любого места (сейчас — long-press по вкладке "Контакты" в MainTabsActivity).
     */
    public static void showFiltered(Context context, String filter) {
        if (context == null) {
            return;
        }
        ScrollView scrollView = new ScrollView(context);
        TextView textView = new TextView(context);
        textView.setTextIsSelectable(true);
        textView.setTextSize(12);
        textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setText(getFiltered(filter));
        scrollView.addView(textView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Логи Поток" + (filter != null && !filter.isEmpty() ? " (фильтр: " + filter + ")" : ""));
        builder.setView(scrollView);
        builder.setPositiveButton("Копировать", (dialog, which) -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Поток debug log", getFiltered(filter)));
            }
        });
        builder.setNeutralButton("Очистить", (dialog, which) -> clear());
        builder.setNegativeButton("Закрыть", null);
        builder.show();
    }

    /**
     * Ставит собственный UncaughtExceptionHandler ПЕРЕД уже существующим (не заменяет
     * его, а оборачивает): при краше сначала пишет стектрейс в этот кольцевой буфер
     * (через FileLog, т.е. он попадёт и в logcat), затем вызывает оригинальный
     * обработчик — чтобы поведение самого Telegram/системы на краше не менялось.
     * Безопасно вызывать несколько раз — повторные вызовы игнорируются.
     */
    // ФИКС "краш происходит, но в логах его не видно": installCrashHandler и раньше
    // ловил исключение и писал его через d() — НО d() кладёт строку только в
    // in-memory ArrayDeque (см. поле lines выше). При фатальном необработанном
    // исключении сразу ПОСЛЕ этого вызова происходит previous.uncaughtException(),
    // который убивает процесс — вся оперативная память, включая этот буфер,
    // исчезает вместе с ним ДО того, как пользователь успевает открыть экран
    // логов. Именно поэтому после каждого краша буфер оказывался чистым/обычным,
    // как будто краша не было вообще. Теперь стектрейс СНАЧАЛА синхронно
    // записывается в файл на диске (переживает смерть процесса), а при следующем
    // запуске приложения (см. вызов loadPendingCrashIfAny ниже) этот файл
    // читается и его содержимое подставляется в начало буфера — так его можно
    // увидеть тем же способом (долгое нажатие на "Контакты"), что и обычные логи.
    private static final String CRASH_FILE_NAME = "potok_last_crash.txt";

    public static synchronized void installCrashHandler(Context context) {
        if (crashHandlerInstalled) {
            return;
        }
        crashHandlerInstalled = true;
        final Context appContext = context != null ? context.getApplicationContext() : null;
        hangFileContext = appContext;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String text = fmt.format(new Date()) + " [CRASH] Uncaught in thread "
                    + thread.getName() + ": " + Log_getStackTraceString(throwable);
                d("CRASH", text);
                writeCrashFile(appContext, text);
            } catch (Throwable ignore) {
                // никогда не даём диагностике сломать доставку краша дальше
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
        // При установке хендлера (т.е. при запуске приложения) сразу проверяем,
        // не остался ли файл краша с прошлого запуска — если да, подгружаем его
        // в буфер первыми строками и удаляем файл, чтобы не показывать повторно.
        loadPendingCrashIfAny(appContext);
        // Аналогично — хвост лога с прошлого запуска (на случай, если тот запуск
        // закончился не крашем, а зависанием и убийством процесса вручную).
        loadPendingHangTailIfAny(appContext);
    }

    private static synchronized void loadPendingHangTailIfAny(Context context) {
        if (context == null) {
            return;
        }
        java.io.File f = new java.io.File(context.getFilesDir(), HANG_FILE_NAME);
        if (!f.exists()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            String[] tailLines = sb.toString().split("\n");
            for (int i = tailLines.length - 1; i >= 0; i--) {
                if (!tailLines[i].isEmpty()) {
                    lines.addFirst(tailLines[i]);
                }
            }
            lines.addFirst("════════ ХВОСТ ЛОГА С ПРОШЛОГО ЗАПУСКА (ниже, на случай зависания) ════════");
        } catch (Throwable ignore) {
            // не даём падению чтения хвоста сломать запуск приложения
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void writeCrashFile(Context context, String text) {
        if (context == null) {
            return;
        }
        try (java.io.FileOutputStream fos = context.openFileOutput(CRASH_FILE_NAME, Context.MODE_PRIVATE)) {
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } catch (Throwable ignore) {
            // диагностика не должна мешать доставке краша дальше
        }
    }

    private static synchronized void loadPendingCrashIfAny(Context context) {
        if (context == null) {
            return;
        }
        java.io.File f = new java.io.File(context.getFilesDir(), CRASH_FILE_NAME);
        if (!f.exists()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            // ВАЖНО: addFirst() в порядке чтения перевернул бы строки задом
            // наперёд — вставляем в обратном порядке итерации, чтобы после
            // всех addFirst() строки читались сверху вниз в исходной
            // последовательности (маркер должен оказаться самым первым).
            String[] crashLines = sb.toString().split("\n");
            for (int i = crashLines.length - 1; i >= 0; i--) {
                lines.addFirst(crashLines[i]);
            }
            lines.addFirst("════════ КРАШ С ПРОШЛОГО ЗАПУСКА (ниже) ════════");
        } catch (Throwable ignore) {
            // не даём падению чтения краш-файла сломать запуск приложения
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String Log_getStackTraceString(Throwable t) {
        if (t == null) {
            return "(null throwable)";
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
