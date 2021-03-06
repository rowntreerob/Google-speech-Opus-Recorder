package com.google.cloud.android.speech;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

import top.oply.opuslib.OpusTool;
import top.oply.opuslib.OpusTrackInfo;


/**
 * Replacement for orig. 'VoiceRecorder' that adds Lib integration for recorded audio output
 * as Opus file.
 * Continuously records audio and notifies the {@link VoiceRecorder.Callback} when voice (or any
 * sound) is heard.
 *
 * <p>The recorded audio format is always {@link AudioFormat#ENCODING_PCM_16BIT} and
 * {@link AudioFormat#CHANNEL_IN_MONO}. This class will automatically pick the right sample rate
 * for the device. Use {@link #getSampleRate()} to get the selected value.</p>
 */
public class OpusRecorder {

 //   private static final int[] SAMPLE_RATE_CANDIDATES = new int[]{16000, 11025, 22050, 44100};
 private static final int[] SAMPLE_RATE_CANDIDATES = new int[]{16000};
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    //private static final int AMPLITUDE_THRESHOLD = 1500;
    private static final int AMPLITUDE_THRESHOLD = 3000;
    private static final int SPEECH_TIMEOUT_MILLIS = 1400;
    private static final int MAX_SPEECH_LENGTH_MILLIS = 12 * 1000;
    //private static final int MAX_SPEECH_LENGTH_MILLIS = 30 * 1000;
    private static final String TAG = "OpusRecorder";

    private static final int STATE_NONE = 0;
    private static final int STATE_STARTED = 1;
    private volatile int state = STATE_NONE;

    public static abstract class Callback {

        /**
         * Called when the recorder starts hearing voice.
         */
        public void onVoiceStart() {
        }

        /**
         * Called when the recorder is hearing voice.
         *
         * @param data The audio data in {@link AudioFormat#ENCODING_PCM_16BIT}.
         * @param size The size of the actual data in {@code data}.
         */
        public void onVoice(byte[] data, int size) {
        }

        /**
         * Called when the recorder stops hearing voice.
         */
        public void onVoiceEnd() {
        }
    }

    private final Callback mCallback;

    private AudioRecord mAudioRecord;

    private Thread mThread;

    private byte[] mBuffer;
    private int bufferSize = 0;

    private final Object mLock = new Object();
    private String filePath;

    /** The timestamp of the last time that voice is heard. */
    private long mLastVoiceHeardMillis = Long.MAX_VALUE;

    /** The timestamp when the current voice is started. */
    private long mVoiceStartedMillis;
    private ByteBuffer firstBuffer = ByteBuffer.allocateDirect(1920);
    private ByteBuffer fileBuffer = ByteBuffer.allocateDirect(1920);// Should be 1920, to accord with function writeFreme()
    public OpusTool mOpusTool;

    public OpusRecorder(@NonNull Callback callback) {
        mCallback = callback;
    }
    //bug try singleton :: opus_android vrn of recorder
    private static volatile OpusRecorder oRecorder ;
    public static OpusRecorder getInstance(Callback callback){
        if(oRecorder == null)
            synchronized(OpusRecorder.class){
                if(oRecorder == null)
                    oRecorder = new OpusRecorder(callback);
            }
        return oRecorder;
    }


    /**
     * Init the lib's OpusTool with a new file. writes the header in the encoded(opus) file
     * and awaits the microphone buffer for further IO
     * Encode PCM16 mono @16k frame/sec TO Opus 2400 mono
     * config details of encoder? belo
     * https://github.com/louisyonge/opus_android/blob/master/opuslib/src/main/jni/opustool/opusaudio.c#L289
     */
    public void init() {

        mOpusTool = new OpusTool();
        filePath = getNextFile();
        //Log.d(TAG, "initOn , startRec " +filePath);
        int rst = mOpusTool.startRecording(filePath);
    }

    private String getNextFile(){
        return OpusTrackInfo.getInstance().getAValidFileName("OpusRecord");
    }

    /**
     * Starts recording audio.
     *1. audioRecrd START
     *2. trackInfo GetFilNM
     *3. opusTool.startRecording(file
     *4. Thread new/start
     *5. ReadWrite LOOP on buffer inside Thread
     * <p>The caller is responsible for calling {@link #stop()} later.</p>
     */
    public void start() {
        // Stop recording if it is currently ongoing.
        stop();
        // Try to create a new recording session.
        mAudioRecord = createAudioRecord(); //dont hava file yet
        //Log.d(TAG, "start java.audio.record");
        mAudioRecord.startRecording();
        if (mAudioRecord == null) {
            throw new RuntimeException("Cannot instantiate VoiceRecorder");
        }
        init(); //getNxt file now calls opusTool.startrecording(filNM

        // Start recording.


        //mAudioRecord.startRecording();
        state = STATE_STARTED;
        // Start processing the captured audio.
        mThread = new Thread(new ProcessVoice());
        mThread.start();
    }

    /**
     * Stops recording audio and the encoderTool for Opus.
     */
    public void stop() {
        //Log.d(TAG, "stop");
        synchronized (mLock) {
            state = STATE_NONE;

            if(null != mOpusTool)mOpusTool.stopRecording();
            dismiss();

            //.updateTrackInfo()
            OpusTrackInfo info =  OpusTrackInfo.getInstance();
            if(null != filePath) {
                info.addOpusFile(filePath);
                File f = new File(filePath);}

            // updateTrackInfo end

            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
//bug interupted

            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            }
            mBuffer = null;

        }
//was here updateTrack
    }

    /**
     * Dismisses the currently ongoing utterance.
     */
    public void dismiss() {
        if (mLastVoiceHeardMillis != Long.MAX_VALUE) {
            mLastVoiceHeardMillis = Long.MAX_VALUE;
            mCallback.onVoiceEnd();
        }
    }

    /**
     * Retrieves the sample rate currently used to record audio.
     *
     * @return The sample rate of recorded audio.
     */
    public int getSampleRate() {
        if (mAudioRecord != null) {
            return mAudioRecord.getSampleRate();
        }
        return 0;
    }

    /**
     * Creates a new {@link AudioRecord}.
     *
     * @return A newly created {@link AudioRecord}, or null if it cannot be created (missing
     * permissions?).
     */
    private AudioRecord createAudioRecord() {
        for (int sampleRate : SAMPLE_RATE_CANDIDATES) {

            final int sizeInBytes = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING);
            bufferSize = (sizeInBytes / 1920 + 1) * 1920;
            if (sizeInBytes == AudioRecord.ERROR_BAD_VALUE) {
                continue;
            }
            final AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    sampleRate, CHANNEL, ENCODING, bufferSize);
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                //Log.d(TAG, "createRecdr " + sampleRate +" " +bufferSize);
                mBuffer = new byte[bufferSize];
                return audioRecord;
            } else {
                audioRecord.release();
            }
        }
        return null;
    }

    /**
     * copy from : https://github.com/louisyonge/opus_android/blob/master/opuslib/src/main/java/top/oply/opuslib/OpusRecorder.java#L97
     * make mic's buffer avail for call to jni layer for  OpusTool writeFrame()
     * rev : input type DirectBuffer is now just bytes[]
     */
    private void writeAudioDataToOpus(ByteBuffer buffer, int size) {

        //bytes[] to new Buffer
        ByteBuffer finalBuffer = ByteBuffer.allocateDirect(size);
        finalBuffer.put(buffer);
        //Log.d(TAG, "buffrPut " +buffer.position());

        finalBuffer.rewind();
        boolean flush = false;

        //write data  to Opus file
        //bug mLastVoiceHeardMillis != Long.MAX_VALUE;

        while (state == STATE_STARTED && finalBuffer.hasRemaining()) {
            int oldLimit = -1;
            if (finalBuffer.remaining() > fileBuffer.remaining()) {
                oldLimit = finalBuffer.limit();
                finalBuffer.limit(fileBuffer.remaining() + finalBuffer.position());
            }
            fileBuffer.put(finalBuffer);
            if (fileBuffer.position() == fileBuffer.limit() || flush) {
                int length = !flush ? fileBuffer.limit() : finalBuffer.position();
                int rst = mOpusTool.writeFrame(fileBuffer, length); //to encoder
                if (rst != 0) {
                    fileBuffer.rewind();
                } else{
                    Log.d(TAG, "writeFrame err rewindNO " + rst);
                }
            }
            if (oldLimit != -1) {
                finalBuffer.limit(oldLimit);
            }
        }
    }

    /**
     * Continuously processes the captured audio and notifies {@link #mCallback} of corresponding
     * events.
     * calls OpusEnc with bytes from microphone audio (PCM16-mono to Opus)
     */
    private class ProcessVoice implements Runnable {

        @Override
        public void run() {
            while (true) {
                synchronized (mLock) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    //final int size = mAudioRecord.read(mBuffer, 0, mBuffer.length);
                    final int size = mAudioRecord.read(firstBuffer,  bufferSize); //no chg to buffr state
                    firstBuffer.position(size);
                    firstBuffer.flip();  //propr state for relative gets.... in "writeaudioToOpus"
                    //writeAudioDataToOpus(mBuffer, size);
                    if (size != AudioRecord.ERROR_INVALID_OPERATION) {
                      try {
                          writeAudioDataToOpus(firstBuffer, size);
                      }
                      catch (Exception e)
                      {
                          e.printStackTrace();
                      }
                    } // done buffer get byte[] from the buffer and do the rest
                    firstBuffer.rewind();
                    firstBuffer.get(mBuffer); //what is state of firBffr after this? rewindIT?

                    final long now = System.currentTimeMillis();

                    if (isHearingVoice(mBuffer, size)) {
                        if (mLastVoiceHeardMillis == Long.MAX_VALUE) {
                            mVoiceStartedMillis = now;
                            mCallback.onVoiceStart(); //bug called before Main-> start
                        }
                        mCallback.onVoice(mBuffer, size);
                        mLastVoiceHeardMillis = now;
                        if (now - mVoiceStartedMillis > MAX_SPEECH_LENGTH_MILLIS) {
                            end();
                        }
                    } else if (mLastVoiceHeardMillis != Long.MAX_VALUE) {
                        mCallback.onVoice(mBuffer, size);
                        if (now - mLastVoiceHeardMillis > SPEECH_TIMEOUT_MILLIS) {
                            end();
                        }
                    }
                }
            }
        }

        private void end() {
            stop();
            mOpusTool.stopRecording();
            mLastVoiceHeardMillis = Long.MAX_VALUE;
            mCallback.onVoiceEnd();
        }

        private boolean isHearingVoice(byte[] buffer, int size) {
            for (int i = 0; i < size - 1; i += 2) {
                // The buffer has LINEAR16 in little endian.
                int s = buffer[i + 1];
                if (s < 0) s *= -1;
                s <<= 8;
                s += Math.abs(buffer[i]);
                if (s > AMPLITUDE_THRESHOLD) {
                    return true;
                }
            }
            return false;
        }

    }

}
