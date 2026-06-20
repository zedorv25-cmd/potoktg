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

    public static void show(Context context) {
        String logs;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int lineCount = 0;
            java.util.List<String> allLines = new java.util.ArrayList<>();
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
            reader.close();
            // Берём последние 300 строк, чтобы не перегружать диалог
            int start = Math.max(0, allLines.size() - 300);
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
