package org.telegram.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

public class PotokDebugLog {

    private static final String CRASH_FILE_NAME = "potok_last_crash.txt";
    private static final String LOG_FILE_NAME = "potok_debug_log.txt";

    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** Пишет строку в файл лога — надёжнее logcat */
    public static void log(String tag, String message) {
        if (appContext == null) return;
        try {
            File file = new File(appContext.getFilesDir(), LOG_FILE_NAME);
            FileWriter writer = new FileWriter(file, true); // append
            writer.write("[" + tag + "] " + new java.util.Date() + "\n" + message + "\n---\n");
            writer.close();
        } catch (Exception ignored) {}
    }

    /** Очищает файл лога */
    public static void clearLog() {
        if (appContext == null) return;
        try {
            new File(appContext.getFilesDir(), LOG_FILE_NAME).delete();
        } catch (Exception ignored) {}
    }

    /** Показывает файл лога */
    public static void showLog(Context context) {
        File file = new File(context.getFilesDir(), LOG_FILE_NAME);
        String content;
        if (!file.exists()) {
            content = "Лог пуст (файл " + LOG_FILE_NAME + " не найден).\nУбедись что PotokDebugLog.init() вызван в ApplicationLoader.";
        } else {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            } catch (Exception e) {
                sb.append("Ошибка чтения: ").append(e.getMessage());
            }
            content = sb.toString();
        }
        showTextDialog(context, "Debug Log (" + LOG_FILE_NAME + ")", content);
    }

    public static void installCrashHandler(Context context) {
        appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File file = new File(context.getFilesDir(), CRASH_FILE_NAME);
                FileWriter writer = new FileWriter(file, false);
                writer.write("Время краша: " + new java.util.Date() + "\n\n");
                writer.write(android.util.Log.getStackTraceString(throwable));
                writer.close();
            } catch (Exception ignored) {}
            if (previousHandler != null) previousHandler.uncaughtException(thread, throwable);
            else System.exit(2);
        });
    }

    public static void showLastCrash(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE_NAME);
        String content;
        if (!file.exists()) {
            content = "Сохранённого краша нет.";
        } else {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            } catch (Exception e) {
                sb.append("Ошибка чтения: ").append(e.getMessage());
            }
            content = sb.toString();
        }
        showTextDialog(context, "Последний краш", content);
    }

    public static void showFiltered(Context context, String filterKeyword) {
        // Сначала пробуем файл лога
        File file = new File(context.getFilesDir(), LOG_FILE_NAME);
        if (file.exists()) {
            StringBuilder sb = new StringBuilder();
            try {
                String fullContent = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                // Каждая запись — это блок "[TAG] дата\nсообщение\n---\n".
                // Фильтруем по блокам целиком, а не построчно — иначе тег попадает в фильтр,
                // а сам текст сообщения (следующая строка, без тега) отсеивается напрасно.
                String[] entries = fullContent.split("---\n");
                for (String entry : entries) {
                    if (entry.trim().isEmpty()) continue;
                    if (filterKeyword == null || entry.contains(filterKeyword)) {
                        sb.append(entry).append("---\n");
                    }
                }
            } catch (Exception e) {
                sb.append("Ошибка: ").append(e.getMessage());
            }
            String content = sb.toString().isEmpty() ? "Нет записей с \"" + filterKeyword + "\" в файле лога." : sb.toString();
            showTextDialog(context, "Лог: \"" + filterKeyword + "\"", content);
            return;
        }
        // Fallback: logcat
        String logs;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (filterKeyword == null || line.contains(filterKeyword)) sb.append(line).append("\n");
            }
            reader.close();
            logs = sb.toString().isEmpty() ? "Нет строк, содержащих \"" + filterKeyword + "\"." : sb.toString();
        } catch (Exception e) {
            logs = "Ошибка logcat: " + e.getMessage();
        }
        showTextDialog(context, "Лог: \"" + filterKeyword + "\"", logs);
    }

    public static void show(Context context) {
        showFiltered(context, null);
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
            .setPositiveButton("Скопировать", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("potok_logs", content));
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Очистить лог", (dialog, which) -> clearLog())
            .setNeutralButton("Закрыть", null)
            .show();
    }
}
