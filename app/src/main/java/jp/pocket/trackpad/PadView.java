package jp.pocket.trackpad;

import android.content.Context;
import android.graphics.*;
import android.view.*;

final class PadView extends View {
    private final Hid hid;
    private final Paint paint = new Paint(3);
    private final float density;
    private final float[] dotsX = new float[10], dotsY = new float[10];
    private int dots, fingers, maxFingers, mode;
    private float lastX, lastY, lastSpan, travel, sumX, sumY, remainderX, remainderY, scrollX, scrollY, zoom;
    private long started, lastTap;
    private boolean ending, fired, dragging;
    float sensitivity = 1.5f;
    boolean natural = true;

    PadView(Context context, Hid hid) {
        super(context); this.hid = hid; density = getResources().getDisplayMetrics().density;
        setFocusable(true); setContentDescription("トラックパッド。1本指で移動、2本指でスクロール、3・4本指でジェスチャー");
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.rgb(20,29,44));
        paint.setColor(Color.rgb(36,49,66));
        for (float x = 20*density; x < getWidth(); x += 24*density)
            for (float y = 20*density; y < getHeight(); y += 24*density) canvas.drawCircle(x,y,density,paint);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(18*density); paint.setColor(0xFF9DAFC5);
        canvas.drawText(dragging ? "ドラッグ中" : "Pocket Trackpad",getWidth()/2f,getHeight()/2f,paint);
        paint.setColor(0x885EDFC5);
        for (int i=0;i<dots;i++) canvas.drawCircle(dotsX[i],dotsY[i],23*density,paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        dots = Math.min(10,e.getPointerCount());
        for (int i=0;i<dots;i++) { dotsX[i]=e.getX(i); dotsY[i]=e.getY(i); }
        if (action == MotionEvent.ACTION_DOWN) {
            fingers = maxFingers = 1; mode = 0; ending = fired = false;
            travel = sumX = sumY = remainderX = remainderY = scrollX = scrollY = zoom = 0;
            started = e.getEventTime();
            if (lastTap > 0 && started-lastTap < 280) { dragging = true; hid.button(1,true); }
            lastTap = 0; baseline(e);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && !ending) {
            if (dragging) { hid.button(1,false); dragging = false; }
            fingers = e.getPointerCount(); maxFingers = Math.max(maxFingers,fingers);
            sumX = sumY = 0; mode = 0; baseline(e);
        } else if (action == MotionEvent.ACTION_MOVE && !ending) {
            float x = center(e,true), y = center(e,false), span = span(e);
            float dx=(x-lastX)/density, dy=(y-lastY)/density, ds=(span-lastSpan)/density;
            lastX=x; lastY=y; lastSpan=span;
            travel += Math.hypot(dx,dy); sumX += dx; sumY += dy;
            if (fingers == 1) {
                remainderX += dx*sensitivity; remainderY += dy*sensitivity;
                int mx=(int)remainderX, my=(int)remainderY;
                if (mx!=0 || my!=0) hid.mouse(mx,my,0,0);
                remainderX -= mx; remainderY -= my;
            } else if (fingers == 2) {
                zoom += ds;
                if (mode == 0) {
                    if (Math.abs(zoom)>10 && Math.abs(zoom)>Math.hypot(sumX,sumY)*1.3) mode=2;
                    else if (Math.hypot(sumX,sumY)>8) mode=1;
                }
                if (mode == 2) {
                    int steps=(int)(zoom/24);
                    if (steps!=0) { hid.zoom(steps); zoom -= steps*24; fired=true; }
                } else if (mode == 1) {
                    scrollX += dx; scrollY += dy;
                    int sx=(int)(scrollX/18), sy=(int)(scrollY/18);
                    if (sx!=0 || sy!=0) {
                        hid.mouse(0,0, natural ? sy : -sy, natural ? -sx : sx);
                        scrollX -= sx*18; scrollY -= sy*18; fired=true;
                    }
                }
            } else if (!fired && Math.hypot(sumX,sumY)>60) {
                boolean horizontal=Math.abs(sumX)>Math.abs(sumY)*1.2;
                boolean vertical=Math.abs(sumY)>Math.abs(sumX)*1.2;
                if (fingers==3 && horizontal) { hid.shortcut(sumX>0 ? 4 : 6,0x2B); fired=true; }
                else if (fingers==3 && vertical) { hid.shortcut(8,sumY<0 ? 0x2B : 0x07); fired=true; }
                else if (fingers==4 && horizontal) { hid.shortcut(9,sumX>0 ? 0x4F : 0x50); fired=true; }
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            // Do not interpret remaining fingers as a new gesture while lifting.
            ending = true;
        } else if (action == MotionEvent.ACTION_UP) {
            if (dragging) { hid.button(1,false); dragging=false; }
            else if (!fired && travel<10 && Math.abs(zoom)<10 && e.getEventTime()-started<250) {
                if (maxFingers==1) { hid.click(1); lastTap=e.getEventTime(); performClick(); }
                else if (maxFingers==2) hid.click(2);
            }
            dots=0;
        }
        invalidate(); return true;
    }
    private float center(MotionEvent e, boolean x) {
        float sum=0; for(int i=0;i<e.getPointerCount();i++) sum += x ? e.getX(i) : e.getY(i);
        return sum/e.getPointerCount();
    }
    private float span(MotionEvent e) {
        return e.getPointerCount()==2 ? (float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0)) : 0;
    }
    private void baseline(MotionEvent e) { lastX=center(e,true); lastY=center(e,false); lastSpan=span(e); }
    void cancel() { hid.release(); dragging=false; ending=true; lastTap=0; dots=0; invalidate(); }
    @Override public boolean performClick() { super.performClick(); return true; }
}
