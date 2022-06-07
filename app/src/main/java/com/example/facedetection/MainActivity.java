package com.example.facedetection;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.text.InputType;
import android.util.Pair;
import android.util.Size;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;


public class MainActivity extends AppCompatActivity{
    PreviewView previewView;
    ImageView imageView;
    ImageButton camButton, switchCam, saveFace;
    CameraSelector cameraSelector;
    ProcessCameraProvider cameraProvider;
    GraphicOverlay graphicOverlay;
    boolean cameraOpen = false, start = true, flipV = false;
    static final int inputSize = 112, outputSize = 192;
    static final float imgMean = 128.0f, imgSTD = 128.0f;
    int lensFacing = CameraSelector.LENS_FACING_BACK; //Default Back Camera

    Interpreter tfLite;
    float[][] embeddings;
    private final HashMap<String, SimilarityClassifier.Recognition> registered = new HashMap<>(); //saved Faces

    //(*)////////////////////////////////////////////////////////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView); previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        camButton = findViewById(R.id.button_cam);
        switchCam = findViewById(R.id.switch_cam); switchCam.setVisibility(View.INVISIBLE);
        imageView = findViewById(R.id.image_view);
        graphicOverlay = findViewById(R.id.graphic_overlay);
        saveFace = findViewById(R.id.save_face); saveFace.setVisibility(View.INVISIBLE);

        /////////////////////////////////////////////////////////

        camButton.setOnClickListener(v -> {
            //Camera Permission
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
            }
            if(!cameraOpen){
                setupCamera();
                switchCam.setVisibility(View.VISIBLE);
                cameraOpen = true;
            }else{
                cameraProvider.unbindAll();
                switchCam.setVisibility(View.INVISIBLE);
                cameraOpen = false;
            }
        });

        /////////////////////////////////////////////////////////

        switchCam.setOnClickListener(v -> {

            if(lensFacing == CameraSelector.LENS_FACING_BACK){
                lensFacing = CameraSelector.LENS_FACING_FRONT;
                flipV = true;
            }
            else{
                lensFacing = CameraSelector.LENS_FACING_BACK;
                flipV = false;
            }
            cameraProvider.unbindAll();
            setupCamera();
        });

        /////////////////////////////////////////////////////////

        saveFace.setOnClickListener(v -> {
            start = false;
            AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Enter Name");

            // Set up the input
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setMaxWidth(50);
            builder.setView(input);

            builder.setPositiveButton("Add", (dialog, which) -> {
                //Create and Initialize new object with Face embeddings and Name.
                SimilarityClassifier.Recognition result = new SimilarityClassifier.Recognition("0", "", -1f);
                result.setExtra(embeddings);

                registered.put(input.getText().toString(), result);
                start = true;
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> {
                start = true;
                dialog.cancel();
            });
            builder.show();
        });

        /////////////////////////////////////////////////////////

        try {
            String modelFile = "mobile_face_net.tflite"; //file name in assets
            tfLite = new Interpreter(loadModelFile(this, modelFile));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    //(*)

    private ByteBuffer loadModelFile(@NonNull Activity activity, String MODEL_FILE)  throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    //(*)

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        cameraProviderFuture.addListener(() -> {
            try
            {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            }
            catch (ExecutionException | InterruptedException e)
            { // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    //(*)

    @SuppressLint("UnsafeOptInUsageError")
    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                //.setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) //Latest frame is shown
                .build();

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), imageProxy -> {

            if (imageProxy.getImage() == null) {
                Toast.makeText(this, "proxy empty", Toast.LENGTH_SHORT).show();
                return;
            }
                InputImage inputImage = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
                //Toast.makeText(this, "Rotation " + imageProxy.getImageInfo().getRotationDegrees(), Toast.LENGTH_SHORT).show();

                //Initialize Face Detector
                FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .build();

                FaceDetector detector = FaceDetection.getClient(options);

                detector.process(inputImage)
                        .addOnSuccessListener(faces -> detect(faces, inputImage))
                        .addOnFailureListener(e -> Toast.makeText(this, "detect process failure", Toast.LENGTH_SHORT).show())
                        .addOnCompleteListener(task ->  imageProxy.close()
                        );
        });
        cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, preview);
    }

    //(*)////////////////////////////////////////////////////////

    private void detect(List<Face> faces, InputImage inputImage) {
        String name = null;
        if (faces.size() > 0){
            //Toast.makeText(this, R.string.face_detected, Toast.LENGTH_SHORT).show();
            Face face = faces.get(0); //the first face detected

            Bitmap bmp = mediaImgToBmp(inputImage.getMediaImage(),
                    inputImage.getRotationDegrees(),
                    face.getBoundingBox());

            imageView.setImageBitmap(bmp);
            saveFace.setVisibility(View.VISIBLE);

            if(start) name = recognize(bmp);
            if(name != null) graphicOverlay.draw(face.getBoundingBox(), name);
        }
        else
        {
            if(registered.isEmpty())
                Toast.makeText(this, "no faces added", Toast.LENGTH_SHORT).show();
            else
                Toast.makeText(this, R.string.no_face_detected, Toast.LENGTH_SHORT).show();
        }
    }

    public String recognize(final Bitmap bitmap){
        ByteBuffer imgData = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4); //Create ByteBuffer to store normalized image
        imgData.order(ByteOrder.nativeOrder());

        int[] pixels = new int[inputSize * inputSize];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight()); //get pixel values from Bitmap to normalize
        imgData.rewind();

        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int pixelValue = pixels[i * inputSize + j];
                imgData.putFloat((((pixelValue >> 16) & 0xFF) - imgMean) / imgSTD);
                imgData.putFloat((((pixelValue >> 8) & 0xFF) - imgMean) / imgSTD);
                imgData.putFloat(((pixelValue & 0xFF) - imgMean) / imgSTD);
            }
        }
        //imgData is input to our model
        Object[] inputArray = {imgData};
        Map<Integer, Object> outputMap = new HashMap<>();

        embeddings = new float[1][outputSize]; //output of model will be stored in this variable
        outputMap.put(0, embeddings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap); //Run model


        float distance;
        //Compare new face with saved Faces.
        if (registered.size() > 0) {
            final Pair<String, Float> nearest = findNearest(embeddings[0]); //Find closest matching face
            if (nearest != null) {
                final String name = nearest.first;
                distance = nearest.second;
                if(distance < 1.000f) return name; //If distance between closest found face is more than 1.000 ,then UNKNOWN
                else return "unknown";
            }
        }
        else
            Toast.makeText(this, "register equals 0", Toast.LENGTH_SHORT).show();
        return null;
    }

    //(*) Compare Faces by distance between face embeddings
    // looks for the nearest embedding in the dataset (using L2 norm) and returns the pair <id, distance>
    private Pair<String, Float> findNearest(float[] emb) {

        Pair<String, Float> ret = null;
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet()) {
            final String name = entry.getKey();
            final float[] knownEmb = ((float[][]) entry.getValue().getExtra())[0];

            float distance = 0;
            for (int i = 0; i < emb.length; i++) {
                float diff = emb[i] - knownEmb[i];
                distance += diff*diff;
            }
            distance = (float) Math.sqrt(distance);
            if (ret == null || distance < ret.second) {
                ret = new Pair<>(name, distance);
            }
        }
        return ret;
    }

    //(*)////////////////////////////////////////////////////////

    private Bitmap mediaImgToBmp(Image mediaImage, int rotationDegrees, @NonNull Rect boundingBox) {
        Bitmap frame_bmp = toBitmap(mediaImage);
        //Adjust orientation of Face
        Bitmap frame_bmp1 = rotateBitmap(frame_bmp, rotationDegrees, flipV);

        //Crop out bounding box from whole Bitmap(image)
        RectF adjBoundingBox = new RectF(boundingBox.left,
                boundingBox.top,
                boundingBox.right,
                boundingBox.bottom);
        Bitmap cropped_face = getCropBitmapByCPU(frame_bmp1, adjBoundingBox);

        return ResizeBitmap(cropped_face);
    }

    //(*)

    private Bitmap ResizeBitmap(@NonNull Bitmap bm) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) inputSize) / width;
        float scaleHeight = ((float) inputSize) / height;

        Matrix matrix = new Matrix(); // CREATE A MATRIX FOR THE MANIPULATION
        matrix.postScale(scaleWidth, scaleHeight); // RESIZE THE BIT MAP

        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false); // "RECREATE" THE NEW BITMAP
        bm.recycle();
        return resizedBitmap;
    }

    //(*)

    private static Bitmap getCropBitmapByCPU(Bitmap source, @NonNull RectF cropRectF) {
        Bitmap resultBitmap = Bitmap.createBitmap((int) cropRectF.width(),
                (int) cropRectF.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resultBitmap);

        // draw background
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawRect(//from  w w  w. ja v  a  2s. c  om
                new RectF(0, 0, cropRectF.width(), cropRectF.height()),
                paint);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-cropRectF.left, -cropRectF.top);

        canvas.drawBitmap(source, matrix, paint);

        if (source != null && !source.isRecycled()) {
            source.recycle();
        }
        return resultBitmap;
    }

    //(*)

    private static Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees, boolean flip) {
        Matrix matrix = new Matrix();

        // Rotate the image back to straight.
        matrix.postRotate(rotationDegrees);

        // Mirror the image along the X or Y axis.
        matrix.postScale(flip ? -1.0f : 1.0f, 1.0f);
        Bitmap rotatedBitmap =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // Recycle the old bitmap if it has changed.
        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }
        return rotatedBitmap;
    }

    //(*)

    private static byte[] YUV_420_888toNV21(@NonNull Image image) {

        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width*height;
        int uvSize = width*height/4;

        byte[] nv21 = new byte[ySize + uvSize*2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int rowStride = image.getPlanes()[0].getRowStride();
        assert(image.getPlanes()[0].getPixelStride() == 1);

        int pos = 0;

        if (rowStride == width) { // likely
            yBuffer.get(nv21, 0, ySize);
            pos += ySize;
        }
        else {
            long yBufferPos = -rowStride; // not an actual position
            for (; pos<ySize; pos+=width) {
                yBufferPos += rowStride;
                yBuffer.position((int) yBufferPos);
                yBuffer.get(nv21, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        assert(rowStride == image.getPlanes()[1].getRowStride());
        assert(pixelStride == image.getPlanes()[1].getPixelStride());

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            try {
                vBuffer.put(1, (byte)~savePixel);
                if (uBuffer.get(0) == (byte)~savePixel) {
                    vBuffer.put(1, savePixel);
                    vBuffer.position(0);
                    uBuffer.position(0);
                    vBuffer.get(nv21, ySize, 1);
                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining());

                    return nv21; // shortcut
                }
            }
            catch (ReadOnlyBufferException ex) {
                // unfortunately, we cannot check if vBuffer and uBuffer overlap
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }

        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
        // but performance gain would be less significant

        for (int row=0; row<height/2; row++) {
            for (int col=0; col<width/2; col++) {
                int vuPos = col*pixelStride + row*rowStride;
                nv21[pos++] = vBuffer.get(vuPos);
                nv21[pos++] = uBuffer.get(vuPos);
            }
        }
        return nv21;
    }

    //(*)

    private Bitmap toBitmap(Image image) {

        byte[] nv21=YUV_420_888toNV21(image);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);

        byte[] imageBytes = out.toByteArray();

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    //(*)

}