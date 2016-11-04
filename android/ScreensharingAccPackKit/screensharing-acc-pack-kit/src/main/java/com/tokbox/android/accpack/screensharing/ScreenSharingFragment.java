package com.tokbox.android.accpack.screensharing;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import com.opentok.android.OpentokError;

import com.tokbox.android.annotations.AnnotationsToolbar;
import com.tokbox.android.annotations.AnnotationsView;
import com.tokbox.android.accpack.screensharing.config.OpenTokConfig;
import com.tokbox.android.annotations.utils.AnnotationsVideoRenderer;
import com.tokbox.android.logging.OTKAnalytics;
import com.tokbox.android.logging.OTKAnalyticsData;
import com.tokbox.android.otsdkwrapper.listeners.BasicListener;
import com.tokbox.android.otsdkwrapper.listeners.ListenerException;
import com.tokbox.android.otsdkwrapper.listeners.PausableBasicListener;
import com.tokbox.android.otsdkwrapper.utils.PreviewConfig;
import com.tokbox.android.otsdkwrapper.wrapper.OTWrapper;

import java.util.UUID;


/**
 * Defines a fragment to represent the ScreenSharing acc-pack
 *
 */
public class ScreenSharingFragment extends Fragment implements ScreenSharingBar.ScreenSharingBarListener{

    private static final String LOG_TAG = ScreenSharingFragment.class.getSimpleName();

    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";
    private static final String ERROR = "ScreenSharing error";
    private static final int REQUEST_MEDIA_PROJECTION = 1;

    private String mApiKey;
    private ScreenSharingListener mListener;

    private VirtualDisplay mVirtualDisplay;
    private int mDensity;
    private int mWidth;
    private int mHeight;

    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private ImageReader mImageReader;

    private int mResultCode;
    private Intent mResultData;

    private AnnotationsView mAnnotationsView;
    private AnnotationsToolbar mAnnotationsToolbar;

    private ScreenSharingBar mScreensharingBar;

    private boolean isStarted = false;
    private boolean isAnnotationsEnabled = false;
    private boolean isRemoteAnnotationsEnabled = false;
    private boolean isAudioEnabled = true;

    private ViewGroup mScreen;

    private AnnotationsVideoRenderer mRenderer;
    private View rootView;

    private OTKAnalyticsData mAnalyticsData;
    private OTKAnalytics mAnalytics;

    private OTWrapper mWrapper;

    ProgressDialog mProgressDialog;


    @Override
    public void onClose() {
        addLogEvent(OpenTokConfig.LOG_ACTION_CLOSE, OpenTokConfig.LOG_VARIATION_ATTEMPT);
        if (isStarted) {
            mAnnotationsView.restart();
            mAnnotationsView = null;
            stop();
            removeScreensharingBar();
            mListener.onClosed();
        }
        addLogEvent(OpenTokConfig.LOG_ACTION_CLOSE, OpenTokConfig.LOG_VARIATION_SUCCESS);
    }

    /**
     * Monitors state changes in the ScreenSharingFragment.
     *
     */
    public interface ScreenSharingListener {

        /**
         * Invoked when screensharing started.
         *
         */
        void onScreenSharingStarted();

        /**
         * Invoked when screensharing stopped.
         *
         */
        void onScreenSharingStopped();


        /**
         * Invoked when a screen sharing error occurs.
         *
         * @param error The error message.
         */
        void onScreenSharingError(String error);


        /**
         * Invoked when the annotations view has been added to the screensharing view.
         *
         * @param view The annotations view.
         */
        void onAnnotationsViewReady(AnnotationsView view);

        /**
         * Invoked when the screensharing has been closed.
         *
         */
        void onClosed();
    }

    /*
     * Constructor.
     */
    public ScreenSharingFragment(){

    }

    public static ScreenSharingFragment newInstance(OTWrapper wrapper, String apiKey) {

        ScreenSharingFragment fragment = new ScreenSharingFragment();

        fragment.mWrapper = wrapper;
        fragment.mApiKey = apiKey;

        return fragment;
    }

    private void init(){
        //Analytics logging
        String source = getContext().getPackageName();

        SharedPreferences prefs = getContext().getSharedPreferences("opentok", Context.MODE_PRIVATE);
        String guidVSol = prefs.getString("guidVSol", null);
        if (null == guidVSol) {
            guidVSol = UUID.randomUUID().toString();
            prefs.edit().putString("guidVSol", guidVSol).commit();
        }

        mAnalyticsData = new OTKAnalyticsData.Builder(OpenTokConfig.LOG_CLIENT_VERSION, source, OpenTokConfig.LOG_COMPONENTID, guidVSol).build();
        mAnalytics = new OTKAnalytics(mAnalyticsData);

        mWrapper.addBasicListener(mBasicListener);

        checkSessionInfo();

        addLogEvent(OpenTokConfig.LOG_ACTION_INITIALIZE, OpenTokConfig.LOG_VARIATION_SUCCESS);
    }

    private void checkSessionInfo(){
        if ( mAnalytics != null ){
            if ( mWrapper != null ) {
                mAnalyticsData.setSessionId(mWrapper.getOTConfig().getSessionId());
                mAnalyticsData.setConnectionId(mWrapper.getOwnConnId());
            }
            if ( mApiKey != null ) {
                mAnalyticsData.setPartnerId(mApiKey);
            }
            mAnalytics. setData(mAnalyticsData);
        }
    }

    private void removeScreensharingBar(){
        if(mScreensharingBar != null ){
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(mScreensharingBar);
            mScreensharingBar = null;
        }
    }

    private void checkAnnotations() {
        if ( isAnnotationsEnabled || isRemoteAnnotationsEnabled ) {
            mAnnotationsToolbar.restart();
        }
    }

    private int dpToPx(int dp) {
        double screenDensity = this.getResources().getDisplayMetrics().density;
        return (int) (screenDensity * (double) dp);
    }

    //add log events
    private void addLogEvent(String action, String variation){
        if ( mAnalytics!= null ) {
            mAnalytics.logEvent(action, variation);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setUpVirtualDisplay() {

        // display metrics
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mDensity = metrics.densityDpi;
        Display mDisplay = getActivity().getWindowManager().getDefaultDisplay();

        Point size = new Point();
        mDisplay.getRealSize(size);
        mWidth = size.x;
        mHeight = size.y;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

        mVirtualDisplay = mMediaProjection.createVirtualDisplay("ScreenCapture", mWidth, mHeight, mDensity, flags, mImageReader.getSurface(), null, null);

        size.set(mWidth, mHeight);

        //create ScreenCapturer
        ScreenSharingCapturer capturer = new ScreenSharingCapturer(getContext(), mScreen, mImageReader);
        if ( mWrapper != null ) {
            mRenderer = new AnnotationsVideoRenderer(getContext());
            PreviewConfig config = new PreviewConfig.PreviewConfigBuilder().
                    name("screenPublisher").capturer(capturer).renderer(mRenderer).build();
            mWrapper.startSharingMedia(config, true);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void tearDownMediaProjection() {
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScreenCapture() {

        if (mMediaProjection != null) {
            Log.i(LOG_TAG, "mMediaProjection != null");

            setUpVirtualDisplay();
        } else if (mResultCode != 0 && mResultData != null) {
            Log.i(LOG_TAG, "mResultCode != 0 && mResultData != null");
            setUpMediaProjection();
            setUpVirtualDisplay();
        } else {
            Log.i(LOG_TAG, "Requesting confirmation");
            startActivityForResult(
                    mMediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION);
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void stopScreenCapture() {
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
        mVirtualDisplay = null;

        tearDownMediaProjection();
    }


    private BasicListener mBasicListener =
            new PausableBasicListener(new BasicListener<OTWrapper>() {

                @Override
                public void onConnected(OTWrapper otWrapper, int participantsCount, String connId, String data) throws ListenerException {

                }

                @Override
                public void onDisconnected(OTWrapper otWrapper, int participantsCount, String connId, String data) throws ListenerException {

                }

                @Override
                public void onPreviewViewReady(OTWrapper otWrapper, View localView) throws ListenerException {

                }

                @Override
                public void onPreviewViewDestroyed(OTWrapper otWrapper, View localView) throws ListenerException {

                }

                @Override
                public void onRemoteViewReady(OTWrapper otWrapper, View remoteView, String remoteId, String data) throws ListenerException {

                }

                @Override
                public void onRemoteViewDestroyed(OTWrapper otWrapper, View remoteView, String remoteId) throws ListenerException {

                }

                @Override
                public void onStartedSharingMedia(OTWrapper otWrapper, boolean screensharing) throws ListenerException {
                    if (screensharing){
                        onScreenSharingStarted();
                        //show connections dialog
                        mProgressDialog.dismiss();

                        checkAnnotations();
                        isStarted = true;
                        try {
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {

                                    if ( isAnnotationsEnabled ) {
                                if (mAnnotationsView == null) {
                                    try {
                                        mAnnotationsView = new AnnotationsView(getContext(), mWrapper, mApiKey, true);
                                        mAnnotationsView.attachToolbar(mAnnotationsToolbar);
                                        mAnnotationsView.setVideoRenderer(mRenderer); //to use screencapture
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                  }
                                onAnnotationsViewReady(mAnnotationsView);
                                mScreen.addView(mAnnotationsView);
                            }
                            mScreensharingBar = new ScreenSharingBar(getContext(), ScreenSharingFragment.this);

                            //add screensharing bar on top of the screen
                                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                            WindowManager.LayoutParams.MATCH_PARENT,
                                            WindowManager.LayoutParams.WRAP_CONTENT,
                                            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                                            0 | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                            PixelFormat.TRANSLUCENT);
                                    params.gravity = Gravity.LEFT | Gravity.TOP;
                                    params.x = 0;
                                    params.y = 0;

                                    WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                                    wm.addView(mScreensharingBar, params);
                                }
                            });

                        }catch(Exception e){
                            Log.i(LOG_TAG, "Exception - onStreamCreated "+e);
                        }
                    }
                }

                @Override
                public void onStoppedSharingMedia(OTWrapper otWrapper, boolean screensharing) throws ListenerException {
                    if (screensharing) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mScreen.removeView(mScreensharingBar);
                                mScreen.removeView(mAnnotationsView);
                                checkAnnotations();
                                onScreenSharingStopped();
                                isStarted = false;
                                mAnnotationsView = null;
                            }});
                    }
                }

                @Override
                public void onRemoteJoined(OTWrapper otWrapper, String remoteId) throws ListenerException {

                }

                @Override
                public void onRemoteLeft(OTWrapper otWrapper, String remoteId) throws ListenerException {

                }

                @Override
                public void onRemoteVideoChange(OTWrapper otWrapper, String remoteId, String reason, boolean videoActive, boolean subscribed) throws ListenerException {

                }

                @Override
                public void onError(OTWrapper otWrapper, OpentokError error) throws ListenerException {
                    onScreenSharingError(ERROR + ": "+error.getMessage());
                    if (isStarted()){
                        addLogEvent(OpenTokConfig.LOG_ACTION_END, OpenTokConfig.LOG_VARIATION_ERROR);
                    }
                    else {
                        addLogEvent(OpenTokConfig.LOG_ACTION_START, OpenTokConfig.LOG_VARIATION_ERROR);
                    }
                }
            });

    /*
     * Set the screen sharing listener.
     * @param mListener The screen sharing listener.
     */
    public void setListener(ScreenSharingListener mListener) {
        this.mListener = mListener;
    }

    /*
    * Start sharing the screen.
    */
    public void start(){
        init();
        if ( mWrapper!= null && mWrapper.getOwnConnId()!= null ) {
            checkSessionInfo(); //add session info to the logging

            if (mVirtualDisplay == null) {
                addLogEvent(OpenTokConfig.LOG_ACTION_START, OpenTokConfig.LOG_VARIATION_ATTEMPT);
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startScreenCapture();
                        //show connections dialog
                        mProgressDialog = new ProgressDialog(getContext());
                        mProgressDialog.setTitle("Please wait");
                        mProgressDialog.setMessage("Starting screen sharing...");
                        mProgressDialog.show();
                    }
                });
            }
        }
    }

    /*
    * Stop sharing the screen.
    */
    public void stop() {
        addLogEvent(OpenTokConfig.LOG_ACTION_END, OpenTokConfig.LOG_VARIATION_ATTEMPT);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stopScreenCapture();
                mWrapper.stopSharingMedia(true);
                removeScreensharingBar();
            }
        });
    }

    /*
    * Check whether screensharing has started.
    * @return <code>true</code> if screensharing started; <code>false</code> otherwise.
    */
    public boolean isStarted() {
        return isStarted;
    }

    /*
    * Enable or disable the annotations in the screensharing.
    * @param annotationsEnabled <code>true</code> if annotations are enabled; <code>false</code> otherwise.
    * @param toolbar The annotations toolbar.
    */
    public void enableAnnotations(boolean annotationsEnabled, AnnotationsToolbar toolbar) {
        isAnnotationsEnabled = annotationsEnabled;
        mAnnotationsToolbar = toolbar;

        //add logging info
        if (annotationsEnabled){
            addLogEvent(OpenTokConfig.LOG_ACTION_ENABLE_ANNOTATIONS, OpenTokConfig.LOG_VARIATION_SUCCESS);
        }
        else {
            addLogEvent(OpenTokConfig.LOG_ACTION_DISABLE_ANNOTATIONS, OpenTokConfig.LOG_VARIATION_SUCCESS);
        }
    }

    /*
    * Enable or disable the audio in the screensharing.
    * @param enabled <code>true</code> if  the audio is enabled; <code>false</code> otherwise.
    */
    public void enableAudioScreensharing(boolean enabled) {
        isAudioEnabled = enabled;

        //add logging info
        if (enabled){
            addLogEvent(OpenTokConfig.LOG_ACTION_ENABLE_SCREENSHARING_AUDIO, OpenTokConfig.LOG_VARIATION_SUCCESS);
        }
        else {
            addLogEvent(OpenTokConfig.LOG_ACTION_DISABLE_SCREENSHARING_AUDIO, OpenTokConfig.LOG_VARIATION_SUCCESS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isStarted) {
            stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isStarted) {
            start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        tearDownMediaProjection();
        removeScreensharingBar();
        addLogEvent(OpenTokConfig.LOG_ACTION_DESTROY, OpenTokConfig.LOG_VARIATION_SUCCESS);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            mResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.main_layout, container, false);
        mScreen = container;

        return rootView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Activity activity = getActivity();
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.densityDpi;
        mMediaProjectionManager = (MediaProjectionManager)
                activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mResultData != null) {
            outState.putInt(STATE_RESULT_CODE, mResultCode);
            outState.putParcelable(STATE_RESULT_DATA, mResultData);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.i(LOG_TAG, "User cancelled screensharing permission");
                onScreenSharingError(ERROR + ": User cancelled screensharing permission");
                return;
            }
            Activity activity = getActivity();
            if (activity == null) {
                onScreenSharingError(ERROR + ": Activity is null");
                return;
            }
            Log.i(LOG_TAG, "Starting screen capture");
            mResultCode = resultCode;
            mResultData = data;
            setUpMediaProjection();
            setUpVirtualDisplay();
        }
    }

    protected void onScreenSharingStarted(){
        if ( mListener != null ){
            mListener.onScreenSharingStarted();
            addLogEvent(OpenTokConfig.LOG_ACTION_START, OpenTokConfig.LOG_VARIATION_SUCCESS);
        }
    }

    protected void onScreenSharingStopped(){
        if ( mListener != null ){
            mListener.onScreenSharingStopped();
            addLogEvent(OpenTokConfig.LOG_ACTION_END, OpenTokConfig.LOG_VARIATION_SUCCESS);
        }
    }

    protected void onScreenSharingError(String error){
        if ( mListener != null ){
            mListener.onScreenSharingError(error);
        }
    }

    protected void onAnnotationsViewReady(AnnotationsView view){
        if ( mListener != null ){
            mListener.onAnnotationsViewReady(view);
        }
    }
}
