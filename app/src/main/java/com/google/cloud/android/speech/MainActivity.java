/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.android.speech;

import com.google.auth.oauth2.AccessToken;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.Loader;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements ApiFragment.Listener,
        MessageDialogFragment.Listener {

    private static final int LOADER_ACCESS_TOKEN = 1;

    private static final String FRAGMENT_API = "api";
    private static final String FRAGMENT_MESSAGE_DIALOG = "message_dialog";

    private static final String STATE_RESULTS = "results";

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 0;
    public static final int REQUEST_READ_EXTERNAL = 2;
    public static final int REQUEST_WRITE_EXTERNAL = 3;
    public static final int REQUEST_MOUNT_UNMOUNT = 4;

    private OpusRecorder mVoiceRecorder;  //new version that loops in an Encoder/Filesink 4 audio
    //private VoiceRecorder mVoiceRecorder;

    private final OpusRecorder.Callback mVoiceCallback = new OpusRecorder.Callback() {

        @Override
        public void onVoiceStart() {
            showStatus(true);
            getApiFragment().startRecognizing(mVoiceRecorder.getSampleRate());
        }

        @Override
        public void onVoice(byte[] data, int size) {
            getApiFragment().recognize(data, size);
        }

        @Override
        public void onVoiceEnd() {
            showStatus(false);
            getApiFragment().finishRecognizing();
        }

    };

    // Resource caches
    private int mColorHearing;
    private int mColorNotHearing;

    // View references
    private TextView mStatus;
    private TextView mText;
    private ResultAdapter mAdapter;
    private RecyclerView mRecyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Resources resources = getResources();
        final Resources.Theme theme = getTheme();
        mColorHearing = ResourcesCompat.getColor(resources, R.color.status_hearing, theme);
        mColorNotHearing = ResourcesCompat.getColor(resources, R.color.status_not_hearing, theme);

        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        mStatus = (TextView) findViewById(R.id.status);
        mText = (TextView) findViewById(R.id.text);

        mRecyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        final ArrayList<String> results = savedInstanceState == null ? null :
                savedInstanceState.getStringArrayList(STATE_RESULTS);
        mAdapter = new ResultAdapter(results);
        mRecyclerView.setAdapter(mAdapter);

        prepareApi();
    }

    /**
     *android:name="android.permission.RECORD_AUDIO"
     *android:name="android.permission.WRITE_EXTERNAL_STORAGE"
     *android:name="android.permission.MOUNT_UNMOUNT_FILESYSTEMS"
     */

    //bug
    //Request requires android.permission.RECORD_AUDIO
    @Override
    protected void onStart() {
        super.onStart();

        Log.d("MAIN", "onStart ");
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS)
                        != PackageManager.PERMISSION_GRANTED
                ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {
                Log.d("Main", "CHK 1 on RECORD PERMIT NEED A GRANT W EXPLAIN");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS
                        },
                        MainActivity.REQUEST_RECORD_AUDIO_PERMISSION);
            } else {
                Log.d("Main", "CHK on RECORD PERMIT NEED A GRANT, NO Explanation to Give");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS},
                        MainActivity.REQUEST_RECORD_AUDIO_PERMISSION);
            }
        } else {
            startVoiceRecorder();
        }


        //Log.d("Main", "NO Permissions FALL thru");

        //startVoiceRecorder();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d("MAIN", "onReqPermitsRSLT");
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (permissions.length == 4 && grantResults.length == 4
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecorder();
            } else {
                showPermissionMessageDialog();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onStop() {
        //stopVoiceRecorder();
        if(mVoiceRecorder !=null) {
            mVoiceRecorder.stop();
            mVoiceRecorder = null;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mAdapter != null) {
            outState.putStringArrayList(STATE_RESULTS, mAdapter.getResults());
        }
    }

    private void startVoiceRecorder() {
        Log.d("MAIN" ,"STARTREC");
        if (mVoiceRecorder != null) {
            mVoiceRecorder.stop();
        }

        //mVoiceRecorder = new OpusRecorder(mVoiceCallback);
        mVoiceRecorder = OpusRecorder.getInstance(mVoiceCallback);
        mVoiceRecorder.start();
    }


    private void showPermissionMessageDialog() {
        MessageDialogFragment
                .newInstance(getString(R.string.permission_message))
                .show(getSupportFragmentManager(), FRAGMENT_MESSAGE_DIALOG);
    }

    @Override
    public void stopVoiceRecorder() {
        Log.d("MAIN" ,"STOPREC");
        if (mVoiceRecorder != null) {
            mVoiceRecorder.stop();
            mVoiceRecorder = null;
        }
    }

    @Override
    public void onSpeechRecognized(final String text, final boolean isFinal) {
        if (isFinal && null != mVoiceRecorder) {
            mVoiceRecorder.dismiss();
        }
        if (mText != null && !TextUtils.isEmpty(text)) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isFinal) {
                        mText.setText(null);
                        mAdapter.addResult(text);
                        mRecyclerView.smoothScrollToPosition(0);
                    } else {
                        mText.setText(text);
                    }
                }
            });
        }
    }

    private void showStatus(final boolean hearingVoice) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStatus.setTextColor(hearingVoice ? mColorHearing : mColorNotHearing);
            }
        });
    }

    @Override
    public void onMessageDialogDismissed() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_RECORD_AUDIO_PERMISSION);
    }

    private ApiFragment getApiFragment() {
        return (ApiFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_API);
    }

    private void prepareApi() {
        if (getApiFragment() == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(new ApiFragment(), FRAGMENT_API)
                    .commit();
        }
        getSupportLoaderManager().initLoader(LOADER_ACCESS_TOKEN, null,
                new LoaderManager.LoaderCallbacks<AccessToken>() {
                    @Override
                    public Loader<AccessToken> onCreateLoader(int id, Bundle args) {
                        return new AccessTokenLoader(MainActivity.this);
                    }

                    @Override
                    public void onLoadFinished(Loader<AccessToken> loader, AccessToken token) {
                        getApiFragment().setAccessToken(token);
                        mStatus.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onLoaderReset(Loader<AccessToken> loader) {
                        // Do nothing
                    }
                });
    }

    private static class ViewHolder extends RecyclerView.ViewHolder {

        TextView text;

        public ViewHolder(LayoutInflater inflater, ViewGroup parent) {
            super(inflater.inflate(R.layout.item_result, parent, false));
            text = (TextView) itemView.findViewById(R.id.text);
        }

    }

    private static class ResultAdapter extends RecyclerView.Adapter<ViewHolder> {

        private final ArrayList<String> mResults = new ArrayList<>();

        public ResultAdapter(ArrayList<String> results) {
            if (results != null) {
                mResults.addAll(results);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()), parent);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.text.setText(mResults.get(position));
        }

        @Override
        public int getItemCount() {
            return mResults.size();
        }

        public void addResult(String result) {
            mResults.add(0, result);
            notifyItemInserted(0);
        }

        public ArrayList<String> getResults() {
            return mResults;
        }

    }

}
