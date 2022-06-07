package com.example.facedetection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import org.jetbrains.annotations.Nullable;

public class GraphicOverlay extends View {

    private final Paint paint = new Paint(), labelPaint = new Paint();
    private RectF rectF = null;
    private String name = null;

    public GraphicOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(rectF != null){
            //the text
            labelPaint.setColor(Color.rgb(255, 120, 50)); //orange
            labelPaint.setTextSize(70);
            labelPaint.setTypeface(Typeface.DEFAULT);
            //the rectangle
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5);
            if(name != null && !name.trim().isEmpty() && !name.equals("unknown")) paint.setColor(Color.GREEN);
            else paint.setColor(Color.RED);
            canvas.drawRect(rectF, paint);
            canvas.drawText(name, rectF.left + 0.0f, rectF.top - 15.0f, labelPaint);
        }
    }

    public void draw(Rect rect, String name){
        this.rectF = new RectF((float) rect.left,
                (float) rect.top,
                (float) rect.right,
                (float) rect.bottom);
        this.name = name;
        postInvalidate();
        requestLayout();
    }
}
