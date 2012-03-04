/*
 * Copyright 2011-2012, Institute of Cybernetics at Tallinn University of Technology
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

package ee.ioc.phon.android.speak;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.PendingIntent.CanceledException;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.os.SystemClock;

import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.LinearLayout;

import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;

import ee.ioc.phon.android.speak.RecognizerIntentService.RecognizerBinder;
import ee.ioc.phon.android.speak.RecognizerIntentService.State;
import ee.ioc.phon.netspeechapi.recsession.RecSessionResult;


/**
 * <p>This activity responds to the following intent types:</p>
 * <ul>
 * <li>android.speech.action.RECOGNIZE_SPEECH</li>
 * <li>android.speech.action.WEB_SEARCH</li>
 * </ul>
 * <p>We have tried to implement the complete interface of RecognizerIntent as of API level 7 (v2.1).</p>
 * 
 * <p>It records audio, transcribes it using a speech-to-text server
 * and returns the result as a non-empty list of Strings.
 * In case of <code>android.intent.action.MAIN</code>,
 * it submits the recorded/transcribed audio to a web search.
 * It never returns an error code,
 * all the errors are processed within this activity.</p>
 * 
 * <p>This activity rewrites the error codes which originally come from the
 * speech recognizer webservice (and which are then rewritten by the net-speech-api)
 * to the RecognizerIntent result error codes. The RecognizerIntent error codes are the
 * following (with my interpretation after the colon):</p>
 * 
 * <ul>
 * <li>RESULT_AUDIO_ERROR: recording of the audio fails</li>
 * <li>RESULT_NO_MATCH: everything worked great just no transcription was produced</li>
 * <li>RESULT_NETWORK_ERROR: cannot reach the recognizer server
 * <ul>
 * <li>Network is switched off on the device</li>
 * <li>The recognizer webservice URL does not exist in the internet</li>
 * </ul>
 * </li>
 * <li>RESULT_SERVER_ERROR: server was reached but it denied service for some reason,
 * or produced results in a wrong format (i.e. maybe it provides a different service)</li>
 * <li>RESULT_CLIENT_ERROR: generic client error
 * <ul>
 * <li>The URLs of the recognizer webservice and/or the grammar were malformed</li>
 * </ul>
 * </li>
 * </ul>
 * 
 * @author Kaarel Kaljurand
 */
public class RecognizerIntentActivity extends Activity {

	private static final String LOG_TAG = RecognizerIntentActivity.class.getName();

	private static final int TASK_CHUNKS_INTERVAL = 1500;
	private static final int TASK_CHUNKS_DELAY = 100;

	// Update the byte count every second
	private static final int TASK_BYTES_INTERVAL = 1000;
	// Start the task almost immediately
	private static final int TASK_BYTES_DELAY = 100;

	// Check for pause / max time limit twice a second
	private static final int TASK_STOP_INTERVAL = 500;
	private static final int TASK_STOP_DELAY = 1000;

	// Check the volume 20 times a second
	private static final int TASK_VOLUME_INTERVAL = 50;
	private static final int TASK_VOLUME_DELAY = 500;

	private static final int DELAY_AFTER_START_BEEP = 200;

	private static final String MSG = "MSG";
	private static final int MSG_TOAST = 1;
	private static final int MSG_RESULT_ERROR = 2;

	private static final String DOTS = "............";

	private Map<Integer, String> mErrorMessages;

	private SharedPreferences mPrefs;

	private TextView mTvPrompt;
	private Button mBStartStop;
	private LinearLayout mLlTranscribing;
	private LinearLayout mLlProgress;
	private LinearLayout mLlError;
	private TextView mTvBytes;
	private Chronometer mChronometer;
	private ImageView mIvVolume;
	private ImageView mIvWaveform;
	private TextView mTvChunks;
	private TextView mTvErrorMessage;
	private List<Drawable> mVolumeLevels;

	private SimpleMessageHandler mMessageHandler = new SimpleMessageHandler();
	private Handler mHandlerBytes = new Handler();
	private Handler mHandlerStop = new Handler();
	private Handler mHandlerVolume = new Handler();
	private Handler mHandlerChunks = new Handler();

	private Runnable mRunnableBytes;
	private Runnable mRunnableStop;
	private Runnable mRunnableVolume;
	private Runnable mRunnableChunks;

	private int mSampleRate;

	// Max recording time in milliseconds
	private int mMaxRecordingTime;

	private URL mServerUrl;
	private URL mGrammarUrl;
	private String mGrammarTargetLang;

	private Resources mRes;

	private int mExtraMaxResults = 0;
	private PendingIntent mExtraResultsPendingIntent;
	private Bundle mExtraResultsPendingIntentBundle;

	private Bundle mExtras;

	private RecognizerIntentService mService;
	private boolean mIsBound = false;
	private boolean mStartRecording = false;
	private int mLevel = 0;

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			Log.i(LOG_TAG, "Service connected");
			mService = ((RecognizerBinder) service).getService();

			mService.setOnResultListener(new RecognizerIntentService.OnResultListener() {
				public boolean onResult(RecSessionResult result) {
					// We trust that getLinearizations() returns a non-null non-empty list.
					ArrayList<String> matches = new ArrayList<String>();
					matches.addAll(result.getLinearizations());
					returnOrForwardMatches(mMessageHandler, matches);
					return true;
				}
			});

			mService.setOnErrorListener(new RecognizerIntentService.OnErrorListener() {
				public boolean onError(int errorCode, Exception e) {
					handleResultError(mMessageHandler, errorCode, "onError", e);
					return true;
				}
			});


			if (mStartRecording && ! mService.isWorking()) {
				startRecording();
			} else {
				setGui();
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			// This is called when the connection with the service has been
			// unexpectedly disconnected -- that is, its process crashed.
			// Because it is running in our same process, we should never
			// see this happen.
			mService = null;
			Log.i(LOG_TAG, "Service disconnected");
		}
	};


	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.recognizer);

		mPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		mErrorMessages = createErrorMessages();

		// Don't shut down the screen
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		mTvPrompt = (TextView) findViewById(R.id.tvPrompt);
		mBStartStop = (Button) findViewById(R.id.bStartStop);
		mLlTranscribing = (LinearLayout) findViewById(R.id.llTranscribing);
		mLlProgress = (LinearLayout) findViewById(R.id.llProgress);
		mLlError = (LinearLayout) findViewById(R.id.llError);
		mTvBytes = (TextView) findViewById(R.id.tvBytes);
		mChronometer = (Chronometer) findViewById(R.id.chronometer);
		mIvVolume = (ImageView) findViewById(R.id.ivVolume);
		mIvWaveform = (ImageView) findViewById(R.id.ivWaveform);
		mTvChunks = (TextView) findViewById(R.id.tvChunks);
		mTvErrorMessage = (TextView) findViewById(R.id.tvErrorMessage);

		mRes = getResources();
		mVolumeLevels = new ArrayList<Drawable>();
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level0));
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level1));
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level2));
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level3));
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level4));
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level5));
		mVolumeLevels.add(mRes.getDrawable(R.drawable.speak_now_level6));

		mExtras = getIntent().getExtras();
		if (mExtras == null) {
			// For some reason getExtras() can return null, we map it
			// to an empty Bundle if this occurs.
			mExtras = new Bundle();
		} else {
			mExtraMaxResults = mExtras.getInt(RecognizerIntent.EXTRA_MAX_RESULTS);
			mExtraResultsPendingIntentBundle = mExtras.getBundle(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);

			Parcelable extraResultsPendingIntentAsParceable = mExtras.getParcelable(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
			if (extraResultsPendingIntentAsParceable != null) {
				//PendingIntent.readPendingIntentOrNullFromParcel(mExtraResultsPendingIntent);
				if (extraResultsPendingIntentAsParceable instanceof PendingIntent) {
					mExtraResultsPendingIntent = (PendingIntent) extraResultsPendingIntentAsParceable;
				} else {
					handleResultError(mMessageHandler, RecognizerIntent.RESULT_CLIENT_ERROR, getString(R.string.errorBadPendingIntent), null);
					return;
				}
			}
		}

		mSampleRate = Integer.parseInt(
				mPrefs.getString(
						getString(R.string.keyRecordingRate),
						getString(R.string.defaultRecordingRate)));

		mMaxRecordingTime = 1000 * Integer.parseInt(
				mPrefs.getString(
						getString(R.string.keyAutoStopAfterTime),
						getString(R.string.defaultAutoStopAfterTime)));

		mStartRecording = mPrefs.getBoolean("keyAutoStart", false);

		PackageNameRegistry wrapper = new PackageNameRegistry(this, getCaller());

		try {
			setUrls(wrapper);
		} catch (MalformedURLException e) {
			// The user has managed to store a malformed URL in the configuration.
			handleResultError(mMessageHandler, RecognizerIntent.RESULT_CLIENT_ERROR, "", e);
		}

		mGrammarTargetLang = Utils.chooseValue(wrapper.getGrammarLang(), mExtras.getString(Extras.EXTRA_GRAMMAR_TARGET_LANG));
	}


	@Override
	public void onStart() {
		super.onStart();

		// Show the length of the current recording in bytes
		mRunnableBytes = new Runnable() {
			public void run() {
				if (mService != null) {
					mTvBytes.setText(Utils.getSizeAsString(mService.getLength()));
				}
				mHandlerBytes.postDelayed(this, TASK_BYTES_INTERVAL);
			}
		};

		// Show the number of audio chunks that have been sent to the server
		mRunnableChunks = new Runnable() {
			public void run() {
				if (mService != null) {
					mTvChunks.setText(makeBar(DOTS, mService.getChunkCount()));
				}
				mHandlerChunks.postDelayed(this, TASK_CHUNKS_INTERVAL);
			}
		};

		// Decide if we should stop recording
		// 1. Max recording time has passed
		// 2. Speaker stopped speaking
		mRunnableStop = new Runnable() {
			public void run() {
				if (mService != null) {
					if (mMaxRecordingTime < (SystemClock.elapsedRealtime() - mService.getStartTime())) {
						Log.i(LOG_TAG, "Max recording time exceeded");
						stopRecording();
					} else if (mPrefs.getBoolean("keyAutoStopAfterPause", true) && mService.isPausing()) {
						Log.i(LOG_TAG, "Speaker finished speaking");
						stopRecording();
					} else {
						mHandlerStop.postDelayed(this, TASK_STOP_INTERVAL);
					}
				}
			}
		};


		mRunnableVolume = new Runnable() {
			public void run() {
				if (mService != null) {
					// TODO: take these from some configuration
					float min = 15.f;
					float max = 30.f;

					float db = mService.getRmsdb();
					final int maxLevel = mVolumeLevels.size() - 1;

					int index = (int) ((db - min) / (max - min) * maxLevel);
					final int level = Math.min(Math.max(0, index), maxLevel);

					if (level != mLevel) {
						mIvVolume.setImageDrawable(mVolumeLevels.get(level));
						mLevel = level;
					}

					mHandlerVolume.postDelayed(this, TASK_VOLUME_INTERVAL);
				}
			}
		};


		mBStartStop.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (mIsBound) {
					if (mService.getState() == State.RECORDING) {
						stopRecording();
					} else {
						startRecording();
					}
				} else {
					mStartRecording = true;
					doBindService();
				}
			}
		});

		doBindService();
	}


	@Override
	public void onStop() {
		super.onStop();
		if (mService != null) {
			mService.setOnResultListener(null);
			mService.setOnErrorListener(null);
		}
		stopAllTasks();
		doUnbindService();

		// If non-empty transcription results were obtained,
		// or BACK was pressed then we stop the service.
		// We do not stop the service if HOME is pressed
		// or the orientation changes.
		if (isFinishing()) {
			stopService(new Intent(this, RecognizerIntentService.class));
		}
	}


	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.recognizer, menu);
		return true;
	}


	/**
	 * The menu is only for developers.
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menuRecognizerShowInput:
			Intent details = new Intent(this, DetailsActivity.class);
			details.putExtra(DetailsActivity.EXTRA_TITLE, (String) null);
			details.putExtra(DetailsActivity.EXTRA_STRING_ARRAY, getDetails());
			startActivity(details);
			return true;
		case R.id.menuRecognizerTest1:
			transcribeFile("test_kaks_minutit_sekundites.flac", "audio/x-flac;rate=16000");
			return true;
		case R.id.menuRecognizerTest3:
			returnOrForwardMatches(mMessageHandler,
					new ArrayList<String>(
							Arrays.asList(mRes.getStringArray(R.array.entriesTestResult))));
			return true;
		default:
			return super.onContextItemSelected(item);
		}
	}


	void doBindService() {
		// This can be called also on an already running service
		startService(new Intent(this, RecognizerIntentService.class));

		bindService(new Intent(this, RecognizerIntentService.class), mConnection, Context.BIND_AUTO_CREATE);
		mIsBound = true;
		Log.i(LOG_TAG, "Service is bound");
	}


	void doUnbindService() {
		if (mIsBound) {
			unbindService(mConnection);
			mIsBound = false;
			mService = null;
			Log.i(LOG_TAG, "Service is UNBOUND");
		}
	}


	private void setGui() {
		if (mService == null) {
			setGuiError(RecognizerIntent.RESULT_CLIENT_ERROR);
			return;
		}
		switch(mService.getState()) {
		case IDLE:
			setGuiInit();
			break;
		case INITIALIZED:
			setGuiInit();
			break;
		case RECORDING:
			setGuiRecording();
			break;
		case PROCESSING:
			setGuiTranscribing(mService.getCurrentRecording());
			break;
		case ERROR:
			setGuiError(mService.getErrorCode());
			break;
		}
	}


	private void setRecorderStyle(int color) {
		mTvBytes.setTextColor(color);
		mChronometer.setTextColor(color);
	}


	private void stopRecording() {
		mService.stop();
		playStopSound();
		setGui();
	}


	private void startAllTasks() {
		mHandlerBytes.postDelayed(mRunnableBytes, TASK_BYTES_DELAY);
		mHandlerStop.postDelayed(mRunnableStop, TASK_STOP_DELAY);
		mHandlerVolume.postDelayed(mRunnableVolume, TASK_VOLUME_DELAY);
		mHandlerChunks.postDelayed(mRunnableChunks, TASK_CHUNKS_DELAY);
	}


	private void stopAllTasks() {
		mHandlerBytes.removeCallbacks(mRunnableBytes);
		mHandlerStop.removeCallbacks(mRunnableStop);
		mHandlerVolume.removeCallbacks(mRunnableVolume);
		mHandlerChunks.removeCallbacks(mRunnableChunks);
		stopChronometer();
	}


	private void setGuiInit() {
		mLlTranscribing.setVisibility(View.GONE);
		mIvWaveform.setVisibility(View.GONE);
		// includes: bytes, chronometer, chunks
		mLlProgress.setVisibility(View.INVISIBLE);
		mTvChunks.setText("");
		setTvPrompt();
		if (mStartRecording) {
			mBStartStop.setVisibility(View.GONE);
			mIvVolume.setVisibility(View.VISIBLE);
		} else {
			mIvVolume.setVisibility(View.GONE);
			mBStartStop.setText(getString(R.string.buttonSpeak));
			mBStartStop.setVisibility(View.VISIBLE);
		}
		mLlError.setVisibility(View.GONE);
	}


	private void setGuiError(int errorCode) {
		mLlTranscribing.setVisibility(View.GONE);
		mIvVolume.setVisibility(View.GONE);
		mIvWaveform.setVisibility(View.GONE);
		// includes: bytes, chronometer, chunks
		mLlProgress.setVisibility(View.GONE);
		setTvPrompt();
		mBStartStop.setText(getString(R.string.buttonSpeak));
		mBStartStop.setVisibility(View.VISIBLE);
		mLlError.setVisibility(View.VISIBLE);
		mTvErrorMessage.setText(mErrorMessages.get(errorCode));
	}


	private void setGuiRecording() {
		mChronometer.setBase(mService.getStartTime());
		startChronometer();
		startAllTasks();
		setTvPrompt();
		mLlProgress.setVisibility(View.VISIBLE);
		mLlError.setVisibility(View.GONE);
		setRecorderStyle(mRes.getColor(R.color.red));
		if (mPrefs.getBoolean("keyAutoStopAfterPause", true)) {
			mBStartStop.setVisibility(View.GONE);
			mIvVolume.setVisibility(View.VISIBLE);
		} else {
			mIvVolume.setVisibility(View.GONE);
			mBStartStop.setText(getString(R.string.buttonStop));
			mBStartStop.setVisibility(View.VISIBLE);
		}
	}


	private void setGuiTranscribing(byte[] bytes) {
		mChronometer.setBase(mService.getStartTime());
		stopChronometer();
		mHandlerBytes.removeCallbacks(mRunnableBytes);
		mHandlerStop.removeCallbacks(mRunnableStop);
		mHandlerVolume.removeCallbacks(mRunnableVolume);
		// Chunk checking keeps running
		mTvBytes.setText(Utils.getSizeAsString(bytes.length));
		setRecorderStyle(mRes.getColor(R.color.grey2));
		mBStartStop.setVisibility(View.GONE);
		mTvPrompt.setVisibility(View.GONE);
		mIvVolume.setVisibility(View.GONE);
		mLlProgress.setVisibility(View.VISIBLE);
		mLlTranscribing.setVisibility(View.VISIBLE);

		// http://stackoverflow.com/questions/5012840/android-specifying-pixel-units-like-sp-px-dp-without-using-xml
		DisplayMetrics metrics = mRes.getDisplayMetrics();
		// This must match the layout_width of the top layout in recognizer.xml
		float dp = 250f;
		int waveformWidth = (int) (metrics.density * dp + 0.5f);
		int waveformHeight = (int) (waveformWidth / 2.5);
		mIvWaveform.setVisibility(View.VISIBLE);
		mIvWaveform.setImageBitmap(Utils.drawWaveform(bytes, waveformWidth, waveformHeight, 0, bytes.length));
	}


	private void setTvPrompt() {
		String prompt = getPrompt();
		if (prompt == null || prompt.length() == 0) {
			mTvPrompt.setVisibility(View.INVISIBLE);
		} else {
			mTvPrompt.setText(prompt);
			mTvPrompt.setVisibility(View.VISIBLE);
		}
	}


	private String getPrompt() {
		if (mExtraResultsPendingIntent == null && getCallingActivity() == null) {
			return getString(R.string.promptDefault);
		}
		return mExtras.getString(RecognizerIntent.EXTRA_PROMPT);
	}


	private void stopChronometer() {
		mChronometer.stop();
	}


	private void startChronometer() {
		mChronometer.start();
	}


	private void startRecording() {
		boolean success = init(Utils.getContentType(mSampleRate));
		if (success) {
			playStartSound();
			SystemClock.sleep(DELAY_AFTER_START_BEEP);
			mService.start(mSampleRate);
			setGui();
		}
	}


	private boolean init(String contentType) {
		int nbest = (mExtraMaxResults > 1) ? mExtraMaxResults : 1;
		return mService.init(
				contentType,
				Utils.makeUserAgentComment(this, getCaller()),
				mServerUrl,
				mGrammarUrl,
				mGrammarTargetLang,
				nbest,
				Utils.getUniqueId(getSharedPreferences(getString(R.string.filePreferences), 0)),
				mExtras.getString(Extras.EXTRA_PHRASE)
				);
	}


	private void setResultIntent(ArrayList<String> matches) {
		Intent intent = new Intent();
		intent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, matches);
		setResult(Activity.RESULT_OK, intent);
	}


	private void toast(String message) {
		Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
	}


	private void playStartSound() {
		playSound(R.raw.explore_begin);
	}


	private void playStopSound() {
		playSound(R.raw.explore_end);
	}


	private void playErrorSound() {
		playSound(R.raw.error);
	}


	private void playSound(int sound) {
		if (mPrefs.getBoolean("keyAudioCues", true)) {
			MediaPlayer.create(this, sound).start();
		}
	}


	/**
	 * <p>Only for developers, i.e. we are not going to localize these strings.</p>
	 */
	private String[] getDetails() {
		String callingActivityClassName = null;
		String callingActivityPackageName = null;
		String pendingIntentTargetPackage = null;
		ComponentName callingActivity = getCallingActivity();
		if (callingActivity != null) {
			callingActivityClassName = callingActivity.getClassName();
			callingActivityPackageName = callingActivity.getPackageName();
		}
		if (mExtraResultsPendingIntent != null) {
			pendingIntentTargetPackage = mExtraResultsPendingIntent.getTargetPackage();
		}
		List<String> info = new ArrayList<String>();
		info.add("ID: " + Utils.getUniqueId(getSharedPreferences(getString(R.string.filePreferences), 0)));
		info.add("User-Agent comment: " + Utils.makeUserAgentComment(this, getCaller()));
		info.add("Calling activity class name: " + callingActivityClassName);
		info.add("Calling activity package name: " + callingActivityPackageName);
		info.add("Pending intent target package: " + pendingIntentTargetPackage);
		info.add("Selected grammar: " + mGrammarUrl);
		info.add("Selected target lang: " + mGrammarTargetLang);
		info.add("Selected server: " + mServerUrl);
		info.addAll(Utils.ppBundle(mExtras));
		return info.toArray(new String[info.size()]);
	}


	private static Message createMessage(int type, String str) {
		Bundle b = new Bundle();
		b.putString(MSG, str);
		Message msg = Message.obtain();
		msg.what = type;
		msg.setData(b);
		return msg;
	}


	public class SimpleMessageHandler extends Handler {
		public void handleMessage(Message msg) {
			Bundle b = msg.getData();
			String msgAsString = b.getString(MSG);
			switch (msg.what) {
			case MSG_TOAST:
				toast(msgAsString);
				break;
			case MSG_RESULT_ERROR:
				playErrorSound();
				stopAllTasks();
				setGuiError(mService.getErrorCode());
				break;
			}
		}
	}


	/**
	 * <p>Returns the transcription results (matches) to the caller,
	 * or sends them to the pending intent. In the latter case we also display
	 * a toast-message with the transcription.
	 * Note that we assume that the given list of matches contains at least one
	 * element.</p>
	 *
	 * <p>In case there is no pending intent and also no caller (i.e. we were launched
	 * via the launcher icon), then we pass the results to the standard web search.</p>
	 * 
	 * TODO: the pending intent result code is currently set to 1234 (don't know what this means)
	 * 
	 * @param handler message handler
	 * @param matches transcription results (one or more)
	 */
	private void returnOrForwardMatches(Handler handler, ArrayList<String> matches) {
		// Throw away matches that the user is not interested in
		if (mExtraMaxResults > 0 && matches.size() > mExtraMaxResults) {
			matches.subList(mExtraMaxResults, matches.size()).clear();
		}

		if (mExtraResultsPendingIntent == null) {
			if (getCallingActivity() == null) {
				Intent intentWebSearch = new Intent(Intent.ACTION_WEB_SEARCH);
				// TODO: pass in all the matches
				intentWebSearch.putExtra(SearchManager.QUERY, matches.get(0));
				startActivity(intentWebSearch);
			} else {
				setResultIntent(matches);
			}
		} else {
			if (mExtraResultsPendingIntentBundle == null) {
				mExtraResultsPendingIntentBundle = new Bundle();
			}
			String match = matches.get(0);
			//mExtraResultsPendingIntentBundle.putString(SearchManager.QUERY, match);
			Intent intent = new Intent();
			intent.putExtras(mExtraResultsPendingIntentBundle);
			// This is for Google Maps, YouTube, ...
			intent.putExtra(SearchManager.QUERY, match);
			// This is for SwiftKey X, ...
			intent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, matches);
			String message = "";
			if (matches.size() == 1) {
				message = match;
			} else {
				message = matches.toString();
			}
			handler.sendMessage(createMessage(MSG_TOAST, String.format(getString(R.string.toastForwardedMatches), message)));
			try {
				// TODO: dummy number 1234
				mExtraResultsPendingIntent.send(this, 1234, intent);
			} catch (CanceledException e) {
				handler.sendMessage(createMessage(MSG_TOAST, e.getMessage()));
			}
		}
		finish();
	}


	/**
	 * <p>Returns the package name of the app that receives the transcription,
	 * or <code>null</code> if the package name could not be resolved.</p>
	 */
	private String getCaller() {
		if (mExtraResultsPendingIntent == null) {
			ComponentName callingActivity = getCallingActivity();
			if (callingActivity != null) {
				return callingActivity.getPackageName();
			}
		} else {
			return mExtraResultsPendingIntent.getTargetPackage();
		}
		return null;
	}


	private void handleResultError(Handler handler, int resultCode, String type, Exception e) {
		if (e != null) {
			Log.e(LOG_TAG, "Exception: " + type + ": " + e.getMessage());
		}
		handler.sendMessage(createMessage(MSG_RESULT_ERROR, mErrorMessages.get(resultCode)));
	}


	private void setUrls(PackageNameRegistry wrapper) throws MalformedURLException {
		// The server URL should never be null
		mServerUrl = new URL(
				Utils.chooseValue(
						wrapper.getServerUrl(),
						mExtras.getString(Extras.EXTRA_SERVER_URL),
						mPrefs.getString(getString(R.string.keyService), getString(R.string.defaultService))
						));

		// If the user has not overridden the grammar then use the app's EXTRA.
		String urlAsString = Utils.chooseValue(wrapper.getGrammarUrl(), mExtras.getString(Extras.EXTRA_GRAMMAR_URL));
		if (urlAsString != null && urlAsString.length() > 0) {
			mGrammarUrl = new URL(urlAsString);
		}
	}


	private static String makeBar(String bar, int len) {
		if (len <= 0) return "";
		if (len >= bar.length()) return Integer.toString(len);
		return bar.substring(0, len);
	}


	private void transcribeFile(String fileName, String contentType) {
		try {
			byte[] bytes = getBytesFromAsset(fileName);
			Log.i(LOG_TAG, "Transcribing bytes: " + bytes.length);
			boolean success = init(contentType);
			if (success) {
				mService.transcribe(bytes);
				setGui();
			}
		} catch (IOException e) {
			// Failed to get data from the asset
			handleResultError(mMessageHandler, RecognizerIntent.RESULT_CLIENT_ERROR, "file", e);
		}
	}


	private byte[] getBytesFromAsset(String assetName) throws IOException {
		InputStream is = getAssets().open(assetName);
		//long length = getAssets().openFd(assetName).getLength();
		return IOUtils.toByteArray(is);
	}


	private Map<Integer, String> createErrorMessages() {
		Map<Integer, String> errorMessages = new HashMap<Integer, String>();
		errorMessages.put(RecognizerIntent.RESULT_AUDIO_ERROR, getString(R.string.errorResultAudioError));
		errorMessages.put(RecognizerIntent.RESULT_CLIENT_ERROR, getString(R.string.errorResultClientError));
		errorMessages.put(RecognizerIntent.RESULT_NETWORK_ERROR, getString(R.string.errorResultNetworkError));
		errorMessages.put(RecognizerIntent.RESULT_SERVER_ERROR, getString(R.string.errorResultServerError));
		errorMessages.put(RecognizerIntent.RESULT_NO_MATCH, getString(R.string.errorResultNoMatch));
		return errorMessages;
	}


	/*
	private void test_upload_from_res_raw() {
		InputStream ins = res.openRawResource(R.raw.test_12345);
		demoMatch = transcribe(ins, ins.available());
	}
	 */
}