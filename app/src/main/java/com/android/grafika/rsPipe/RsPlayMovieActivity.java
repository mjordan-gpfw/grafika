/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika.rsPipe;

import com.android.grafika.MainActivity;
import com.android.grafika.MiscUtils;
import com.android.grafika.MoviePlayer;
import com.android.grafika.R;
import com.android.grafika.SpeedControlCallback;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Play a movie from a file on disk.  Output goes to a TextureView.
 * <p>
 * Currently video-only.
 * <p>
 * Contrast with PlayMovieSurfaceActivity, which uses a SurfaceView.  Much of the code is
 * the same, but here we can handle the aspect ratio adjustment with a simple matrix,
 * rather than a custom layout.
 * <p>
 * TODO: investigate crash when screen is rotated while movie is playing (need
 * to have onPause() wait for playback to stop)
 */
public class RsPlayMovieActivity extends Activity implements OnItemSelectedListener,
    TextureView.SurfaceTextureListener, MoviePlayer.PlayerFeedback {

    private static final String TAG = MainActivity.TAG;

    private TextureView mTextureView;
    private String[] mMovieFiles;
    private int mSelectedMovie;
    private boolean mShowStopLabel;
    private MoviePlayer.PlayTask mPlayTask;
    private boolean mSurfaceTextureReady = false;

    private final Object mStopper = new Object();   // used to signal stop
    private RenderScript mRs;
    private Allocation mInputAllocation;
    private Allocation mOutputAllocation;
    private HandlerThread mRsHandlerThread;
    private Handler mRsHandler;
    private long mTimeStart = 0;
    private long mTimestamp = 0;
    private int mFrames = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_movie);

        mTextureView = (TextureView) findViewById(R.id.movie_texture_view);
        mTextureView.setSurfaceTextureListener(this);

        // Populate file-selection spinner.
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);
        // Need to create one of these fancy ArrayAdapter thingies, and specify the generic layout
        // for the widget itself.
        ensureSourceFileIsCopied();
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                                                                android.R.layout.simple_spinner_item, mMovieFiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        updateControls();

        mRsHandlerThread = new HandlerThread("RsProcessor");
        mRsHandlerThread.start();
        mRsHandler = new Handler(mRsHandlerThread.getLooper());
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "RsPlayMovieActivity onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "RsPlayMovieActivity onPause");
        super.onPause();
        // We're not keeping track of the state in static fields, so we need to shut the
        // playback down.  Ideally we'd preserve the state so that the player would continue
        // after a device rotation.
        //
        // We want to be sure that the player won't continue to send frames after we pause,
        // because we're tearing the view down.  So we wait for it to stop here.
        if (mPlayTask != null) {
            stopPlayback();
            mPlayTask.waitForStop();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        // There's a short delay between the start of the activity and the initialization
        // of the SurfaceTexture that backs the TextureView.  We don't want to try to
        // send a video stream to the TextureView before it has initialized, so we disable
        // the "play" button until this callback fires.
        Log.d(TAG, "SurfaceTexture ready (" + width + "x" + height + ")");
        mSurfaceTextureReady = true;
        updateControls();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        // ignore
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        mSurfaceTextureReady = false;
        // assume activity is pausing, so don't need to update controls
        return true;    // caller should release ST
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        long currentTimeMillis = System.currentTimeMillis();
        if (mTimeStart == 0) {
            mTimeStart = currentTimeMillis;
        }
        if (currentTimeMillis - mTimestamp > 1000) {
            Log.d("bug", "frame rate info\ntime: " + (mTimestamp- mTimeStart) + "\nframes: " + (mFrames+1));
            mTimestamp = currentTimeMillis;
        }

        mFrames++;

    }

    /*
     * Called when the movie Spinner gets touched.
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        mSelectedMovie = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + mSelectedMovie + " '" + mMovieFiles[mSelectedMovie] + "'");
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    /**
     * onClick handler for "play"/"stop" button.
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        if (mShowStopLabel) {
            Log.d(TAG, "stopping movie");
            stopPlayback();
            // Don't update the controls here -- let the task thread do it after the movie has
            // actually stopped.
            //mShowStopLabel = false;
            //updateControls();
        }
        else {
            if (mPlayTask != null) {
                Log.w(TAG, "movie already playing");
                return;
            }
            Log.d(TAG, "starting movie");
            SpeedControlCallback callback = new SpeedControlCallback();
            if (((CheckBox) findViewById(R.id.locked60fps_checkbox)).isChecked()) {
                // TODO: consider changing this to be "free running" mode
                callback.setFixedPlaybackRate(60);
            }
            SurfaceTexture st = mTextureView.getSurfaceTexture();

            Surface surface = new Surface(st);
            MoviePlayer player = null;
            try {
//                new File(getFilesDir(), TEST_VIDEO)
//                new File("/sdcard/front.mp4")
                player = new MoviePlayer(
                    new File("/sdcard/front.mp4"),
                    null,
                    new MoviePlayer.FrameCallback() {
                        @Override
                        public void preRender(long presentationTimeUsec) {

                        }

                        @Override
                        public void postRender() {

                        }

                        @Override
                        public void loopReset() {

                        }
                    });
            }
            catch (IOException ioe) {
                Log.e(TAG, "Unable to play movie", ioe);
                surface.release();
                return;
            }

            mRs = RenderScript.create(this);
            Type rsType = new Type.Builder(mRs, Element.YUV(mRs))
                .setX(player.getVideoWidth())
                .setY(player.getVideoHeight())
                .setYuvFormat(ImageFormat.NV21)
                .create();
            Type yuvNV12 = new Type.Builder(mRs, Element.U8(mRs))
                .setX(player.getVideoWidth())
                .setY(player.getVideoHeight() * 3 / 2)
                .create();

            mInputAllocation = Allocation.createTyped(mRs,
                                                      yuvNV12,
                                                      Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);

            Type rsOutputType = new Type.Builder(mRs, Element.RGBA_8888(mRs))
                .setX(mTextureView.getWidth())
                .setY(mTextureView.getHeight())
                .create();
            Log.d("bug", "video width: " + player.getVideoWidth() + "\nvideo height: " + player.getVideoHeight());
            Log.d("bug", "surface width: " + mTextureView.getWidth() + "\nsurface height: " + mTextureView.getHeight());
            mOutputAllocation = Allocation.createTyped(mRs,
                                                       rsOutputType,
                                                       Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT );

            player.setOutputSurface(mInputAllocation.getSurface());
            mOutputAllocation.setSurface(surface);
//            ScriptC_grayscale scriptC_grayscale = new ScriptC_grayscale(mRs);
//            ScriptC_yuvToRgb scriptC_yuvToRgb = new ScriptC_yuvToRgb(mRs);
//            scriptC_yuvToRgb.set_gW(rsType.getX());
//            scriptC_yuvToRgb.set_gH(rsType.getY());

//            ScriptC_yuvToRgbN12 scriptC_yuvToRgbN12 = new ScriptC_yuvToRgbN12(mRs);
//            scriptC_yuvToRgbN12.set_gW(rsType.getX());
//            scriptC_yuvToRgbN12.set_gH(rsType.getY());
            ScriptC_yuvToRgbFsN12 scriptC_yuvToRgbN12 = new ScriptC_yuvToRgbFsN12(mRs);
            scriptC_yuvToRgbN12.set_gW(rsType.getX());
            scriptC_yuvToRgbN12.set_gH(rsType.getY());

            ProcessingTask processingTask = new ProcessingTask(mRsHandler,
                                                               scriptC_yuvToRgbN12,
                                                               mInputAllocation,
                                                               mOutputAllocation);

            adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight());

            mPlayTask = new MoviePlayer.PlayTask(player, this);
            if (((CheckBox) findViewById(R.id.loopPlayback_checkbox)).isChecked()) {
                mPlayTask.setLoopMode(true);
            }

            mShowStopLabel = true;
            updateControls();
            mPlayTask.execute();
        }
    }

    /**
     * Requests stoppage if a movie is currently playing.  Does not wait for it to stop.
     */
    private void stopPlayback() {
        if (mPlayTask != null) {
            mPlayTask.requestStop();
        }
    }

    @Override   // MoviePlayer.PlayerFeedback
    public void playbackStopped() {
        Log.d(TAG, "playback stopped");
        mShowStopLabel = false;
        mPlayTask = null;
        updateControls();
    }

    /**
     * Sets the TextureView transform to preserve the aspect ratio of the video.
     */
    private void adjustAspectRatio(int videoWidth, int videoHeight) {
        int viewWidth = mTextureView.getWidth();
        int viewHeight = mTextureView.getHeight();
        double aspectRatio = (double) videoHeight / videoWidth;

        int newWidth, newHeight;
        if (viewHeight > (int) (viewWidth * aspectRatio)) {
            // limited by narrow width; restrict height
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        }
        else {
            // limited by short height; restrict width
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        }
        int xoff = (viewWidth - newWidth) / 2;
        int yoff = (viewHeight - newHeight) / 2;
        Log.v(TAG, "video=" + videoWidth + "x" + videoHeight +
            " view=" + viewWidth + "x" + viewHeight +
            " newView=" + newWidth + "x" + newHeight +
            " off=" + xoff + "," + yoff);

        Matrix txform = new Matrix();
        mTextureView.getTransform(txform);
        txform.setScale((float) newWidth / viewWidth, (float) newHeight / viewHeight);
        //txform.postRotate(10);          // just for fun
        txform.postTranslate(xoff, yoff);
        mTextureView.setTransform(txform);
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls() {
        Button play = (Button) findViewById(R.id.play_stop_button);
        if (mShowStopLabel) {
            play.setText(R.string.stop_button_text);
        }
        else {
            play.setText(R.string.play_button_text);
        }
        play.setEnabled(mSurfaceTextureReady);

        // We don't support changes mid-play, so dim these.
        CheckBox check = (CheckBox) findViewById(R.id.locked60fps_checkbox);
        check.setEnabled(!mShowStopLabel);
        check = (CheckBox) findViewById(R.id.loopPlayback_checkbox);
        check.setEnabled(!mShowStopLabel);
    }

    private static final String TEST_VIDEO = "hero.mp4";

    public void ensureSourceFileIsCopied() {
        File file = new File(getFilesDir(), TEST_VIDEO);

        if (file.exists()) {
            return;
        }
        try {
            writeBytesToFile(getAssets().open("hero.mp4"),
                             file);
        }
        catch (IOException e) {
            Log.e(TAG, "failed to write file", e);
        }
    }

    public static void writeBytesToFile(InputStream is, File file) throws IOException {
        FileOutputStream fos = null;
        try {
            byte[] data = new byte[2048];
            int nbread = 0;
            fos = new FileOutputStream(file);
            while ((nbread = is.read(data)) > -1) {
                fos.write(data, 0, nbread);
            }
        }
        catch (Exception ex) {
            Log.e(TAG, "Exception", ex);
        }
        finally {
            if (fos != null) {
                fos.close();
            }
        }
    }

    static class ProcessingTask implements Runnable, Allocation.OnBufferAvailableListener {

        private int mPendingFrames = 0;
        final Handler mProcessingHandler;
        final ScriptC_yuvToRgbFsN12 mScript;
        final Allocation mInputAllocation;
        final Allocation mOutputAllocation;

        public ProcessingTask(Handler processingHandler, ScriptC_yuvToRgbFsN12 script, Allocation inputAllocation, Allocation outputAllocation) {
            mProcessingHandler = processingHandler;
            mScript = script;
            mInputAllocation = inputAllocation;
            mInputAllocation.setOnBufferAvailableListener(this);
            mOutputAllocation = outputAllocation;
        }

        @Override
        public void onBufferAvailable(Allocation a) {
            synchronized (this) {
//                Log.v("bug", "received input allocation\nwidth: " + a.getType()
//                                                                     .getX()
//                    + "\nheight: " + a.getType()
//                                      .getY());
                mPendingFrames++;
                mProcessingHandler.post(this);
            }
        }

        @Override
        public void run() {
            int pendingFrames;
            synchronized (this) {
                pendingFrames = mPendingFrames;
                mPendingFrames = 0;
                mProcessingHandler.removeCallbacks(this);
            }

            mInputAllocation.ioReceive();
            // get new frame into allocation
            // Get to newest input
            for (int i = 1; i < pendingFrames; i++) {
                Log.d("bug", "dropped frame");
                mInputAllocation.ioReceive();
            }

            mScript.set_gCurrentFrame(mInputAllocation);
            mScript.forEach_root(mOutputAllocation);
            mOutputAllocation.ioSend();
        }
    }
}
