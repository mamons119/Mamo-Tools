package it.mamotools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Layout;
import android.view.KeyEvent;
import android.widget.EditText;

import java.util.HashSet;
import java.util.Set;

/** EditText per il codice: carattere a spaziatura fissa, numeri di riga fissi a sinistra, riga corrente evidenziata. */
final class CodeEditText extends EditText {

    interface Keys {
        boolean onKey(int keyCode, KeyEvent e);
    }

    interface SelListener {
        void onSelection();
    }

    private final Paint gutterBg = new Paint(), gutterLine = new Paint(), num = new Paint(Paint.ANTI_ALIAS_FLAG),
            numErr = new Paint(Paint.ANTI_ALIAS_FLAG), cur = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int gutterW = 0;
    private int digits = 0;
    boolean numbers = true;
    Keys keys;
    SelListener selListener;
    final Set<Integer> errLines = new HashSet<>();

    CodeEditText(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setTypeface(Typeface.MONOSPACE);
        setTextColor(0xFFD7DBE3);
        setHintTextColor(0xFF566074);
        setBackground(new ColorDrawable(Ui.BG));
        setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        setHorizontallyScrolling(true);
        setHighlightColor(0x664DD0E1);
        setCursorVisible(true);
        setFocusableInTouchMode(true);
        setLineSpacing(0, 1.15f);
        gutterBg.setColor(0xFF14171D);
        gutterLine.setColor(0xFF262B35);
        num.setColor(0xFF566074);
        num.setTypeface(Typeface.MONOSPACE);
        num.setTextAlign(Paint.Align.RIGHT);
        numErr.set(num);
        numErr.setColor(0xFFFF6B6B);
        cur.setColor(0x22FFFFFF);
        dot.setColor(0xFFFF6B6B);
        applyPadding(1);
    }

    @Override
    public void setTextSize(float sp) {
        super.setTextSize(sp);
        num.setTextSize(getTextSize() * 0.85f);
        numErr.setTextSize(getTextSize() * 0.85f);
        applyPadding(Math.max(1, digits));
    }

    /** Aggiorna la larghezza della colonna dei numeri in base al numero di righe. */
    void updateLines() {
        CharSequence t = getText();
        int n = 1;
        for (int i = 0, len = t.length(); i < len; i++) {
            if (t.charAt(i) == '\n') {
                n++;
            }
        }
        int dg = Math.max(2, String.valueOf(n).length());
        if (dg != digits) {
            applyPadding(dg);
        }
    }

    private void applyPadding(int dg) {
        digits = dg;
        if (numbers) {
            float cw = num.measureText("0");
            gutterW = (int) (dg * cw + 18 * d);
        } else {
            gutterW = 0;
        }
        setPadding(gutterW + (int) (8 * d), (int) (8 * d), (int) (16 * d), (int) (120 * d));
    }

    void setNumbers(boolean on) {
        numbers = on;
        applyPadding(Math.max(2, digits));
        invalidate();
    }

    @Override
    protected void onSelectionChanged(int a, int b) {
        super.onSelectionChanged(a, b);
        if (selListener != null) {
            selListener.onSelection();
        }
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keys != null && keys.onKey(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDraw(Canvas cv) {
        Layout lay = getLayout();
        if (lay == null) {
            super.onDraw(cv);
            return;
        }
        int sx = getScrollX(), sy = getScrollY(), w = getWidth(), h = getHeight();
        int top = getCompoundPaddingTop();
        int sel = getSelectionStart();
        if (sel >= 0 && sel <= getText().length()) {
            int ln = lay.getLineForOffset(sel);
            cv.drawRect(sx + gutterW, lay.getLineTop(ln) + top, sx + w, lay.getLineBottom(ln) + top, cur);
        }
        super.onDraw(cv);
        if (!numbers) {
            return;
        }
        cv.drawRect(sx, sy, sx + gutterW, sy + h, gutterBg);
        cv.drawRect(sx + gutterW - 1, sy, sx + gutterW, sy + h, gutterLine);
        CharSequence t = getText();
        int first = lay.getLineForVertical(Math.max(0, sy - top));
        int last = lay.getLineForVertical(Math.max(0, sy + h - top));
        int count = 0;
        int fs = lay.getLineStart(first);
        for (int i = 0; i < fs; i++) {
            if (t.charAt(i) == '\n') {
                count++;
            }
        }
        int curLn = count + 1;
        for (int i = first; i <= last && i < lay.getLineCount(); i++) {
            int st = lay.getLineStart(i);
            boolean fv = i == 0 || t.charAt(st - 1) == '\n';
            if (fv) {
                if (i > first) {
                    curLn++;
                }
                boolean err = errLines.contains(curLn);
                cv.drawText(String.valueOf(curLn), sx + gutterW - 8 * d, lay.getLineBaseline(i) + top,
                        err ? numErr : num);
                if (err) {
                    cv.drawCircle(sx + 5 * d, (lay.getLineTop(i) + lay.getLineBottom(i)) / 2f + top, 2.5f * d, dot);
                }
            }
        }
    }
}
