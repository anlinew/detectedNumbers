package com.vytjan.opencv_detectedNumbers.activities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.UUID;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.imgproc.Imgproc;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.vytjan.opencv_detectedNumbers.R;

public class FaceDetectionActivity extends Activity implements CvCameraViewListener2 {

    private static final String    TAG                 = "vytas";
    private static final Scalar    FACE_RECT_COLOR     = new Scalar(0, 255, 0, 255);
    public static final int        JAVA_DETECTOR       = 0;

    private Mat                    mRgba;
    private Mat                    mGray;
    private File                   mCascadeFile;
    private CascadeClassifier      mJavaDetector;

    private String[]               mDetectorName;

    private float                  mRelativeFaceSize   = 0.2f;
    private int                    mAbsoluteFaceSize   = 0;

    private CameraBridgeViewBase   mOpenCvCameraView;

    //bluetooth stuff
    private static String btDeviceAddress = "98:D3:31:FC:15:BD";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private Handler handler = new Handler();

    //serial stuff
    private InputStream inStream = null;
    private OutputStream outStream = null;
    private byte[] readBuffer = new byte[1024];
    private int readBufferPosition = 0;
    private byte lineDelimiter = 10;
    private boolean pauseSerialWorker = false;


    //OpenCV library loading
    private BaseLoaderCallback  mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");

                    try {
                        // load cascade file from application resources
                        InputStream is = getResources().openRawResource(R.raw.lbpcascade_frontalface);
                        File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                        mCascadeFile = new File(cascadeDir, "lbpcascade_frontalface.xml");
                        FileOutputStream os = new FileOutputStream(mCascadeFile);

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        is.close();
                        os.close();

                        mJavaDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
                        if (mJavaDetector.empty()) {
                            Log.e(TAG, "Failed to load cascade classifier");
                            mJavaDetector = null;
                        } else
                            Log.i(TAG, "Loaded cascade classifier from " + mCascadeFile.getAbsolutePath());

                        cascadeDir.delete();

                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
                    }

                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    public FaceDetectionActivity() {
        mDetectorName = new String[2];
        mDetectorName[JAVA_DETECTOR] = "Java";

        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //bt adapter instance
        CheckBt();

        setContentView(R.layout.activity_face_detection);

        //add camera view to layout object
        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.fd_activity_surface_view);
        mOpenCvCameraView.setCvCameraViewListener(this);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    //handle camera view exit and BT socket closing
    @Override
    public void onDestroy() {
        super.onDestroy();
        mOpenCvCameraView.disableView();
        if(btSocket != null){
            try {
                btSocket.close();
            } catch (IOException e) {}
        }
    }

    public void onCameraViewStarted(int width, int height) {
        mGray = new Mat();
        mRgba = new Mat();
    }

    public void onCameraViewStopped() {
        mGray.release();
        mRgba.release();
    }


    //instance and counter of #faces
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {

        mRgba = inputFrame.rgba();
        mGray = inputFrame.gray();

        if (mAbsoluteFaceSize == 0) {
            int height = mGray.rows();
            if (Math.round(height * mRelativeFaceSize) > 0) {
                mAbsoluteFaceSize = Math.round(height * mRelativeFaceSize);
            }
        }

        MatOfRect faces = new MatOfRect();

        if (mJavaDetector != null)
            mJavaDetector.detectMultiScale(mGray, faces, 1.1, 2, 2, new Size(mAbsoluteFaceSize, mAbsoluteFaceSize), new Size());

        Rect[] facesArray = faces.toArray();

        //transfer number to BT socket
        writeData(facesArray.length+"");

        Log.i(TAG, facesArray.length+" is a number of faces detected");
        for (int i = 0; i < facesArray.length; i++)
            //draw a green rect around every face
            Imgproc.rectangle(mRgba, facesArray[i].tl(), facesArray[i].br(), FACE_RECT_COLOR, 3);

        return mRgba;
    }


    //establish BT connection and BT socket
    public void bluetoothConnect() {
        Log.d(TAG, "Bluetooth device address: " + btDeviceAddress);
        BluetoothDevice device = btAdapter.getRemoteDevice(btDeviceAddress);
        Log.d(TAG, "Connecting to ... " + device);
        btAdapter.cancelDiscovery();

        try {
            btSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
            btSocket.connect();
            Log.d(TAG, "Connection made.");
            Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getApplicationContext(), "Unable to connect!", Toast.LENGTH_SHORT).show();
            try {
                btSocket.close();
            } catch (IOException e2) {
                Log.d(TAG, "Unable to end the connection");
            }
            Log.d(TAG, "Socket creation failed");
        }
        //starts bluetooth-serial listening thread
        beginListenForData();
    }

    //instance of btsocket listener
    public void beginListenForData()   {

        try {
            inStream = btSocket.getInputStream();
        }catch (IOException e) {}

        Thread workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !pauseSerialWorker)
                {
                    try
                    {
                        int bytesAvailable = inStream.available();

                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            inStream.read(packetBytes);
                            for(int i=0; i<bytesAvailable; i++)
                            {
                                byte b = packetBytes[i];
                                if(b == lineDelimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;
                                    handler.post(new Runnable() {
                                        public void run() {

                                        }
                                    });
                                }else{
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        pauseSerialWorker = true;
                    }
                }
            }
        });
        workerThread.start();
    }

    //write data to bt socket
    private void writeData(String data) {

        if(this.btSocket == null){
            Log.d(TAG, "No socket: ");
            return;
        }
        try {
            outStream = btSocket.getOutputStream();
        } catch (IOException e) {
            Log.d(TAG, "Error BEFORE writting on bluetooh socket: ", e);
        }

        Log.d(TAG, "Sending string" + data);

        try {
            outStream.write(data.getBytes());
        } catch (IOException e) {
            Log.d(TAG, "Error writting on bluetooh socket: ", e);
        }
    }

    //if BT is available, if BT is turned on
    private void CheckBt() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();

        if (!btAdapter.isEnabled()) {
            if(btAdapter == null){
                Toast.makeText(getApplicationContext(), "You don't have bluetooth, Arduino won't work...:(", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getApplicationContext(), "Turn on bluetooth or use the app without Arduino :)", Toast.LENGTH_LONG).show();
            }
        } else {
            bluetoothConnect();
        }
    }
}
