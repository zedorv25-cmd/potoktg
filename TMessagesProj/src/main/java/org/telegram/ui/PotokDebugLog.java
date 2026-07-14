package org.telegram.messenger;

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
 * "Контакты" — ContactsActivity, длинное нажатие на заголовок).
 *
 * КАК УБРАТЬ ПОСЛЕ ДИАГНОСТИКИ: это временный код, не часть финального продукта.
 * Как только причины блюра/двоения кадров найдены и исправлены, все вызовы
 * PotokDebugLog.d(...) и сам этот класс можно смело удалить.
 */
public class PotokDebugLog {

    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static synchronized void d(String tag, String message) {
        String line = fmt.format(new Date()) + " [" + tag + "] " + message;
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        FileLog.d("PotokDebug: " + line);
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

    public static synchronized void clear() {
        lines.clear();
    }
}
