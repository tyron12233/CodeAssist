package com.tyron.vectorparser;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.tyron.vectorparser.model.ClipPathModel;
import com.tyron.vectorparser.model.GroupModel;
import com.tyron.vectorparser.model.PathModel;
import com.tyron.vectorparser.model.VectorModel;
import com.tyron.vectorparser.util.Utils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Stack;

public class DynamicVectorDrawable extends Drawable {

    private XmlPullParser mParser;
    private VectorModel vectorModel;
    private Matrix scaleMatrix;

    private int tempSaveCount;
    private int height;
    private int width;
    private int left;
    private int top;
    private float offsetX;
    private float offsetY;
    private float scaleRatio;
    private float strokeRatio;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;

    private final ProteusContext mContext;

    public DynamicVectorDrawable(ProteusContext context) {
        mContext = context;
    }

    public void setContents(String contents) throws XmlPullParserException {
        mParser = XmlPullParserFactory.newInstance().newPullParser();
        mParser.setInput(new StringReader(contents));
        buildVectorModel();
    }

    private void buildVectorModel() {

        int tempPosition;
        PathModel pathModel = new PathModel();
        vectorModel = new VectorModel();
        GroupModel groupModel = new GroupModel();
        ClipPathModel clipPathModel = new ClipPathModel();
        Stack<GroupModel> groupModelStack = new Stack<>();

        try {
            int event = mParser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = mParser.getName();
                switch (event) {
                    case XmlPullParser.START_TAG:
                        switch (name) {
                            case "vector":
                                tempPosition = getAttrPosition(mParser, "android:viewportWidth");
                                vectorModel.setViewportWidth((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.VECTOR_VIEWPORT_WIDTH);

                                tempPosition = getAttrPosition(mParser, "android:viewportHeight");
                                vectorModel.setViewportHeight((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.VECTOR_VIEWPORT_HEIGHT);

                                tempPosition = getAttrPosition(mParser, "android:alpha");
                                vectorModel.setAlpha((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.VECTOR_ALPHA);

                                tempPosition = getAttrPosition(mParser, "android:name");
                                vectorModel.setName((tempPosition != -1) ?
                                        mParser.getAttributeValue(tempPosition) : null);

                                tempPosition = getAttrPosition(mParser, "android:width");
                                vectorModel.setWidth((tempPosition != -1) ?
                                        Utils.getFloatFromDimensionString(mParser.getAttributeValue(tempPosition), mContext) : DefaultValues.VECTOR_WIDTH);

                                tempPosition = getAttrPosition(mParser, "android:height");
                                vectorModel.setHeight((tempPosition != -1) ?
                                        Utils.getFloatFromDimensionString(mParser.getAttributeValue(tempPosition), mContext) : DefaultValues.VECTOR_HEIGHT);

                                tempPosition = getAttrPosition(mParser, "android:tint");
                                vectorModel.setTint((tempPosition != -1) ?
                                        Utils.getColorFromString(mParser.getAttributeValue(tempPosition), mContext) : 0);
                                break;
                            case "path":
                                pathModel = new PathModel();

                                tempPosition = getAttrPosition(mParser, "android:name");
                                pathModel.setName((tempPosition != -1) ?
                                        mParser.getAttributeValue(tempPosition) : null);

                                tempPosition = getAttrPosition(mParser, "android:fillAlpha");
                                pathModel.setFillAlpha((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_FILL_ALPHA);

                                tempPosition = getAttrPosition(mParser, "android:fillColor");
                                pathModel.setFillColor((tempPosition != -1) ?
                                        Utils.getColorFromString(mParser.getAttributeValue(tempPosition), mContext) : DefaultValues.PATH_FILL_COLOR);

                                tempPosition = getAttrPosition(mParser, "android:fillType");
                                pathModel.setFillType((tempPosition != -1) ?
                                        Utils.getFillTypeFromString(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_FILL_TYPE);

                                tempPosition = getAttrPosition(mParser, "android:pathData");
                                pathModel.setPathData((tempPosition != -1) ?
                                        mParser.getAttributeValue(tempPosition) : null);

                                tempPosition = getAttrPosition(mParser, "android:strokeAlpha");
                                pathModel.setStrokeAlpha((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_STROKE_ALPHA);

                                tempPosition = getAttrPosition(mParser, "android:strokeColor");
                                pathModel.setStrokeColor((tempPosition != -1) ?
                                        Utils.getColorFromString(mParser.getAttributeValue(tempPosition), mContext) : DefaultValues.PATH_STROKE_COLOR);

                                tempPosition = getAttrPosition(mParser, "android:strokeLineCap");
                                pathModel.setStrokeLineCap((tempPosition != -1) ?
                                        Utils.getLineCapFromString(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_STROKE_LINE_CAP);

                                tempPosition = getAttrPosition(mParser, "android:strokeLineJoin");
                                pathModel.setStrokeLineJoin((tempPosition != -1) ?
                                        Utils.getLineJoinFromString(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_STROKE_LINE_JOIN);

                                tempPosition = getAttrPosition(mParser, "android:strokeMiterLimit");
                                pathModel.setStrokeMiterLimit((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_STROKE_MITER_LIMIT);

                                tempPosition = getAttrPosition(mParser, "android:strokeWidth");
                                pathModel.setStrokeWidth((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_STROKE_WIDTH);

                                tempPosition = getAttrPosition(mParser, "android:trimPathEnd");
                                pathModel.setTrimPathEnd((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_TRIM_PATH_END);

                                tempPosition = getAttrPosition(mParser, "android:trimPathOffset");
                                pathModel.setTrimPathOffset((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_TRIM_PATH_OFFSET);

                                tempPosition = getAttrPosition(mParser, "android:trimPathStart");
                                pathModel.setTrimPathStart((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.PATH_TRIM_PATH_START);

                                pathModel.buildPath(false);
                                break;
                            case "group":
                                groupModel = new GroupModel();

                                tempPosition = getAttrPosition(mParser, "android:name");
                                groupModel.setName((tempPosition != -1) ?
                                        mParser.getAttributeValue(tempPosition) : null);

                                tempPosition = getAttrPosition(mParser, "android:pivotX");
                                groupModel.setPivotX((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_PIVOT_X);

                                tempPosition = getAttrPosition(mParser, "android:pivotY");
                                groupModel.setPivotY((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_PIVOT_Y);

                                tempPosition = getAttrPosition(mParser, "android:rotation");
                                groupModel.setRotation((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_ROTATION);

                                tempPosition = getAttrPosition(mParser, "android:scaleX");
                                groupModel.setScaleX((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_SCALE_X);

                                tempPosition = getAttrPosition(mParser, "android:scaleY");
                                groupModel.setScaleY((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_SCALE_Y);

                                tempPosition = getAttrPosition(mParser, "android:translateX");
                                groupModel.setTranslateX((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_TRANSLATE_X);

                                tempPosition = getAttrPosition(mParser, "android:translateY");
                                groupModel.setTranslateY((tempPosition != -1) ?
                                        Float.parseFloat(mParser.getAttributeValue(tempPosition)) : DefaultValues.GROUP_TRANSLATE_Y);

                                groupModelStack.push(groupModel);
                                break;
                            case "clip-path":
                                clipPathModel = new ClipPathModel();

                                tempPosition = getAttrPosition(mParser, "android:name");
                                clipPathModel.setName((tempPosition != -1) ? mParser.getAttributeValue(tempPosition) : null);

                                tempPosition = getAttrPosition(mParser, "android:pathData");
                                clipPathModel.setPathData((tempPosition != -1) ? mParser.getAttributeValue(tempPosition) : null);

                                clipPathModel.buildPath(false);
                                break;
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if (name.equals("path")) {
                            if (groupModelStack.size() == 0) {
                                vectorModel.addPathModel(pathModel);
                            } else {
                                groupModelStack.peek().addPathModel(pathModel);
                            }
                            vectorModel.getFullpath().addPath(pathModel.getPath());
                        } else if (name.equals("clip-path")) {
                            if (groupModelStack.size() == 0) {
                                vectorModel.addClipPathModel(clipPathModel);
                            } else {
                                groupModelStack.peek().addClipPathModel(clipPathModel);
                            }
                        } else if (name.equals("group")) {
                            GroupModel topGroupModel = groupModelStack.pop();
                            if (groupModelStack.size() == 0) {
                                topGroupModel.setParent(null);
                                vectorModel.addGroupModel(topGroupModel);
                            } else {
                                topGroupModel.setParent(groupModelStack.peek());
                                groupModelStack.peek().addGroupModel(topGroupModel);
                            }
                        } else if (name.equals("vector")) {
                            vectorModel.buildTransformMatrices();
                        }
                        break;
                }
                event = mParser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (vectorModel == null) {
            return;
        }

        if (scaleMatrix == null) {
            int temp1 = (int) vectorModel.getWidth();
            int temp2 = (int) vectorModel.getHeight();

            setBounds(0, 0, temp1, temp2);
        }

        setAlpha(Utils.getAlphaFromFloat(vectorModel.getAlpha()));

        if (left != 0 || top != 0) {
            tempSaveCount = canvas.save();
            canvas.translate(left, top);
            vectorModel.drawPaths(canvas, offsetX, offsetY, scaleX, scaleY);
            canvas.restoreToCount(tempSaveCount);
        } else {
            vectorModel.drawPaths(canvas, offsetX, offsetY, scaleX, scaleY);
        }
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return Utils.dpToPx((int) vectorModel.getWidth());
    }

    @Override
    public int getIntrinsicHeight() {
        return Utils.dpToPx((int) vectorModel.getHeight());
    }

    private void buildScaleMatrix() {
        scaleMatrix = new Matrix();

        scaleMatrix.postTranslate(width / 2f - vectorModel.getViewportWidth() / 2, height / 2f - vectorModel.getViewportHeight() / 2);

        float widthRatio = width / vectorModel.getViewportWidth();
        float heightRatio = height / vectorModel.getViewportHeight();
        float ratio = Math.min(widthRatio, heightRatio);

        scaleRatio = ratio;

        scaleMatrix.postScale(ratio, ratio, width / 2f, height / 2f);
    }

    private void scaleAllPaths() {
        vectorModel.scaleAllPaths(scaleMatrix);
    }

    private void scaleAllStrokes() {
        strokeRatio = Math.min(width / vectorModel.getWidth(), height / vectorModel.getHeight());
        vectorModel.scaleAllStrokeWidth(strokeRatio);
    }

    private int getAttrPosition(XmlPullParser xpp, String attrName) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if (xpp.getAttributeName(i).equals(attrName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        if (bounds.width() != 0 && bounds.height() != 0) {
            left = bounds.left;
            top = bounds.top;

            width = bounds.width();
            height = bounds.height();

            buildScaleMatrix();
            scaleAllPaths();
            scaleAllStrokes();
        }
    }
}
