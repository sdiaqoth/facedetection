package com.example.facedetection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import org.jetbrains.annotations.Nullable;

public class GraphicOverlay extends View {

    private final Paint paint = new Paint();
    private RectF rectF = null;

    public GraphicOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5);

        if(rectF != null)
            canvas.drawRect(rectF, paint);
    }

    public void draw(Rect rect){
        this.rectF = new RectF((float) rect.left,
                (float) rect.top,
                (float) rect.right,
                (float) rect.bottom);
        postInvalidate();
        requestLayout();
    }
}
