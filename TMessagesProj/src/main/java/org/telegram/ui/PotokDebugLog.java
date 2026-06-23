package org.telegram.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Временный инструмент для просмотра логов приложения прямо на телефоне, без adb.
 * Читает собственный logcat процесса (доступен без спец-разрешений на всех версиях Android,
 * так как приложение всегда видит свои собственные сообщения).
 *
 * Использование: PotokDebugLog.show(context);
 * Убрать после завершения отладки.
 */
public class PotokDebugLog {

    private static final String CRASH_FILE_NAME = "potok_last_crash.txt";

    /**
     * Вызывать один раз при старте приложения (например в Application.onCreate()).
     * Перехватывает необработанные краши и сохраняет полный стектрейс в файл —
     * надёжнее logcat, потому что не зависит от системного буфера, который
     * может вымыть строки до того как успеешь открыть диалог логов.
     */
    public static void installCrashHandler(Context context) {
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.File file = new java.io.File(context.getFilesDir(), CRASH_FILE_NAME);
                java.io.FileWriter writer = new java.io.FileWriter(file, false);
                writer.write("Время краша: " + new java.util.Date() + "\n\n");
                writer.write(android.util.Log.getStackTraceString(throwable));
                writer.close();
            } catch (Exception ignored) {
                // если даже запись в файл не удалась — отдаём дальше системному обработчику как есть
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    /** Показывает сохранённый файл краша (если он есть) — самый надёжный источник, не зависит от logcat. */
    public static void showLastCrash(Context context) {
        java.io.File file = new java.io.File(context.getFilesDir(), CRASH_FILE_NAME);
        String content;
        if (!file.exists()) {
            content = "Сохранённого краша нет (файл " + CRASH_FILE_NAME + " не найден).";
        } else {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("Ошибка чтения файла краша: ").append(e.getMessage());
            }
            content = sb.toString();
        }
        showTextDialog(context, "Последний краш (из файла)", content);
    }

    private static void showTextDialog(Context context, String title, String content) {
        TextView textView = new TextView(context);
        textView.setText(content);
        textView.setTextIsSelectable(true);
        textView.setTextSize(11);
        textView.setPadding(24, 24, 24, 24);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView);

        new AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Скопировать всё", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("potok_logs", content);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    public static void show(Context context) {
        String logs;
        try {
            // -b main,crash,system: crash-буфер хранит FATAL EXCEPTION отдельно и дольше,
            // даже если main уже вымыло другими событиями после перезапуска процесса.
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-b", "main", "-b", "crash", "-b", "system"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            java.util.List<String> allLines = new java.util.ArrayList<>();
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
            reader.close();
            // Берём последние 1500 строк вместо 300 — краш и события до него чаще остаются видны
            int start = Math.max(0, allLines.size() - 1500);
            for (int i = start; i < allLines.size(); i++) {
                sb.append(allLines.get(i)).append("\n");
            }
            logs = sb.toString();
            if (logs.isEmpty()) {
                logs = "Лог пуст или нет доступа к logcat на этом устройстве.";
            }
        } catch (Exception e) {
            logs = "Ошибка чтения логов: " + e.getMessage() + "\n\n" + android.util.Log.getStackTraceString(e);
        }

        final String finalLogs = logs;

        TextView textView = new TextView(context);
        textView.setText(finalLogs);
        textView.setTextIsSelectable(true);
        textView.setTextSize(11);
        textView.setPadding(24, 24, 24, 24);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView);

        new AlertDialog.Builder(context)
            .setTitle("Логи приложения (последние 300 строк)")
            .setView(scrollView)
            .setPositiveButton("Скопировать всё", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("potok_logs", finalLogs);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    /**
     * Вариант с фильтром — показывает только строки, содержащие указанный тег/слово.
     * Полезно чтобы не листать весь лог, а сразу увидеть нужное (например "GlassTabView" или "AndroidRuntime").
     */
    public static void showFiltered(Context context, String filterKeyword) {
        String logs;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (filterKeyword == null || filterKeyword.isEmpty() || line.contains(filterKeyword)) {
                    sb.append(line).append("\n");
                }
            }
            reader.close();
            logs = sb.toString();
            if (logs.isEmpty()) {
                logs = "Нет строк, содержащих \"" + filterKeyword + "\".";
            }
        } catch (Exception e) {
            logs = "Ошибка чтения логов: " + e.getMessage();
        }

        final String finalLogs = logs;

        TextView textView = new TextView(context);
        textView.setText(finalLogs);
        textView.setTextIsSelectable(true);
        textView.setTextSize(11);
        textView.setPadding(24, 24, 24, 24);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView);

        new AlertDialog.Builder(context)
            .setTitle("Логи: фильтр \"" + filterKeyword + "\"")
            .setView(scrollView)
            .setPositiveButton("Скопировать всё", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("potok_logs", finalLogs);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }
}
