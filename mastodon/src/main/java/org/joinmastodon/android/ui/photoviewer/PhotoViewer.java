package org.joinmastodon.android.ui.photoviewer;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Property;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.BindableViewHolder;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.FragmentRootLinearLayout;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import okio.Source;

public class PhotoViewer implements ZoomPanView.Listener {
	private static final String TAG = "PhotoViewer";
	public static final int PERMISSION_REQUEST = 926;

	private Activity activity;
	private List<Attachment> attachments;
	private int currentIndex;
	private WindowManager wm;
	private Listener listener;
	private Status status;
	private String accountID;

	private FrameLayout windowView;
	private FragmentRootLinearLayout uiOverlay;
	private ViewPager2 pager;
	private ColorDrawable background = new ColorDrawable(0xff000000);
	private ArrayList<ExoPlayer> players = new ArrayList<>();
	private int screenOnRefCount = 0;
	private Toolbar toolbar;
	private View toolbarWrap;
	private SeekBar videoSeekBar;
	private TextView videoTimeView;
	private ImageButton videoPlayPauseButton;
	private View videoControls;
	private boolean uiVisible = true;
	private AudioManager.OnAudioFocusChangeListener audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
		@Override
		public void onAudioFocusChanged(int focusChange) {
			PhotoViewer.this.onAudioFocusChanged(focusChange);
		}
	};
	private Runnable uiAutoHider = new Runnable() {
		@Override
		public void run() {
			if (uiVisible)
				toggleUI();
		}
	};
	private Animator currentSheetRelatedToolbarAnimation;

	private boolean videoPositionNeedsUpdating;
	private Runnable videoPositionUpdater = new Runnable() {
		@Override
		public void run() {
			updateVideoPosition();
		}
	};
	private int videoDuration, videoInitialPosition, videoLastTimeUpdatePosition;
	private long videoInitialPositionTime;

	private static final Property<FragmentRootLinearLayout, Integer> STATUS_BAR_COLOR_PROPERTY = new Property<FragmentRootLinearLayout, Integer>(Integer.class, "Fdsafdsa") {
		@Override
		public Integer get(FragmentRootLinearLayout object) {
			return object.getStatusBarColor();
		}

		@Override
		public void set(FragmentRootLinearLayout object, Integer value) {
			object.setStatusBarColor(value);
		}
	};

	public PhotoViewer(Activity activity, List<Attachment> attachments, int index, Status status, String accountID, Listener listener) {
		this.activity = activity;
		// 严格还原原始过滤逻辑
		Attachment targetAttachment = attachments.get(index);
		this.attachments = attachments.stream().filter(a -> a.type == Attachment.Type.IMAGE || a.type == Attachment.Type.GIFV || a.type == Attachment.Type.VIDEO).collect(Collectors.toList());
		// 关键点：重映射索引，防止点击即闪退
		currentIndex = this.attachments.indexOf(targetAttachment);
		if (currentIndex < 0) currentIndex = 0;
		this.listener = listener;
		this.status = status;
		this.accountID = accountID;

		wm = activity.getWindowManager();

		windowView = new FrameLayout(activity) {
			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
					if (event.getAction() == KeyEvent.ACTION_DOWN) {
						onStartSwipeToDismissTransition(0f);
					}
					return true;
				}
				return false;
			}

			@Override
			public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
				if (Build.VERSION.SDK_INT >= 29) {
					DisplayCutout cutout = insets.getDisplayCutout();
					Insets tappable = insets.getTappableElementInsets();
					if (cutout != null) {
						int leftInset = Math.max(0, cutout.getSafeInsetLeft() - tappable.left);
						int rightInset = Math.max(0, cutout.getSafeInsetRight() - tappable.right);
						toolbarWrap.setPadding(leftInset, 0, rightInset, 0);
						videoControls.setPadding(leftInset, 0, rightInset, 0);
					} else {
						toolbarWrap.setPadding(0, 0, 0, 0);
						videoControls.setPadding(0, 0, 0, 0);
					}
					insets = insets.replaceSystemWindowInsets(tappable.left, tappable.top, tappable.right, insets.getSystemWindowInsetBottom());
				}
				uiOverlay.dispatchApplyWindowInsets(insets);
				int bottomInset = insets.getSystemWindowInsetBottom();
				if (bottomInset > 0 && bottomInset < V.dp(36)) {
					uiOverlay.setPadding(uiOverlay.getPaddingLeft(), uiOverlay.getPaddingTop(), uiOverlay.getPaddingRight(), V.dp(36));
				}
				return insets.consumeSystemWindowInsets();
			}
		};
		windowView.setBackground(background);
		background.setAlpha(0);
		pager = new ViewPager2(activity);
		pager.setAdapter(new PhotoViewAdapter());
		pager.setCurrentItem(currentIndex, false);
		pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
			@Override
			public void onPageSelected(int position) {
				onPageChanged(position);
			}
		});
		windowView.addView(pager);
		pager.setMotionEventSplittingEnabled(false);

		uiOverlay = (FragmentRootLinearLayout) activity.getLayoutInflater().inflate(R.layout.photo_viewer_ui, windowView, false);
		windowView.addView(uiOverlay);
		uiOverlay.setStatusBarColor(0x80000000);
		uiOverlay.setNavigationBarColor(0x80000000);
		toolbarWrap = uiOverlay.findViewById(R.id.toolbar_wrap);
		toolbar = uiOverlay.findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				onStartSwipeToDismissTransition(0);
			}
		});

		toolbar.getMenu()
				.add(R.string.download)
				.setIcon(R.drawable.ic_fluent_arrow_download_24_regular)
				.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						saveCurrentFile();
						return true;
					}
				})
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		toolbar.getMenu()
				.add(R.string.button_share)
				.setIcon(R.drawable.ic_fluent_share_24_regular)
				.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						shareCurrentFile();
						return true;
					}
				})
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

		if (status != null) {
			toolbar.getMenu()
					.add(R.string.info)
					.setIcon(R.drawable.ic_fluent_info_24_regular)
					.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
						@Override
						public boolean onMenuItemClick(MenuItem item) {
							showInfoSheet();
							return true;
						}
					})
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}

		uiOverlay.setAlpha(0f);
		videoControls = uiOverlay.findViewById(R.id.video_player_controls);
		videoSeekBar = uiOverlay.findViewById(R.id.seekbar);
		videoTimeView = uiOverlay.findViewById(R.id.time);
		videoPlayPauseButton = uiOverlay.findViewById(R.id.play_pause_btn);
		if (this.attachments.get(currentIndex).type != Attachment.Type.VIDEO) {
			videoControls.setVisibility(View.GONE);
		} else {
			videoDuration = (int) Math.round(this.attachments.get(currentIndex).getDuration() * 1000);
			videoLastTimeUpdatePosition = -1;
			updateVideoTimeText(0);
		}

		WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
		wlp.type = WindowManager.LayoutParams.TYPE_APPLICATION;
		wlp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
				| WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
		wlp.format = PixelFormat.TRANSLUCENT;
		wlp.setTitle(activity.getString(R.string.media_viewer));
		if (Build.VERSION.SDK_INT >= 28)
			wlp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		windowView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
		wm.addView(windowView, wlp);

		windowView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
			@Override
			public boolean onPreDraw() {
				windowView.getViewTreeObserver().removeOnPreDrawListener(this);

				Rect rect = new Rect();
				int[] radius = new int[4];
				if (listener.startPhotoViewTransition(currentIndex, rect, radius)) {
					RecyclerView rv = (RecyclerView) pager.getChildAt(0);
					BaseHolder holder = (BaseHolder) rv.findViewHolderForAdapterPosition(currentIndex);
					if (holder != null)
						holder.zoomPanView.animateIn(rect, radius);
				}

				return true;
			}
		});

		videoPlayPauseButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				ExoPlayer player = findCurrentVideoPlayer();
				if (player != null) {
					if (player.isPlaying())
						pauseVideo();
					else
						resumeVideo();
					hideUiDelayed();
				}
			}
		});
		videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					float p = progress / 10000f;
					updateVideoTimeText(Math.round(p * videoDuration));
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				stopUpdatingVideoPosition();
				if (!uiVisible)
					toggleUI();
				windowView.removeCallbacks(uiAutoHider);
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				ExoPlayer player = findCurrentVideoPlayer();
				if (player != null) {
					float progress = seekBar.getProgress() / 10000f;
					player.seekTo((long) (progress * player.getDuration()));
				}
				hideUiDelayed();
			}
		});
	}

	public void removeMenu() {
		if (toolbar != null)
			toolbar.getMenu().clear();
	}

	@Override
	public void onTransitionAnimationUpdate(float translateX, float translateY, float scale) {
		listener.setTransitioningViewTransform(translateX, translateY, scale);
	}

	@Override
	public void onTransitionAnimationFinished() {
		listener.endPhotoViewTransition();
	}

	@Override
	public void onSetBackgroundAlpha(float alpha) {
		background.setAlpha(Math.round(alpha * 255f));
		uiOverlay.setAlpha(Math.max(0f, alpha * 2f - 1f));
	}

	@Override
	public void onStartSwipeToDismiss() {
		listener.setPhotoViewVisibility(pager.getCurrentItem(), false);
		if (!uiVisible) {
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN));
		} else {
			windowView.removeCallbacks(uiAutoHider);
		}
	}

	@Override
	public void onStartSwipeToDismissTransition(float velocityY) {
		pauseVideo();
		WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) windowView.getLayoutParams();
		wlp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
		windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | (activity.getWindow().getDecorView().getSystemUiVisibility() & (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)));
		wm.updateViewLayout(windowView, wlp);

		int index = pager.getCurrentItem();
		listener.setPhotoViewVisibility(index, true);
		Rect rect = new Rect();
		int[] radius = new int[4];
		if (listener.startPhotoViewTransition(index, rect, radius)) {
			RecyclerView rv = (RecyclerView) pager.getChildAt(0);
			BaseHolder holder = (BaseHolder) rv.findViewHolderForAdapterPosition(index);
			if (holder != null)
				holder.zoomPanView.animateOut(rect, radius, velocityY);
		} else {
			windowView.animate()
					.alpha(0)
					.setDuration(300)
					.setInterpolator(CubicBezierInterpolator.DEFAULT)
					.withEndAction(new Runnable() {
						@Override
						public void run() {
							wm.removeView(windowView);
						}
					})
					.start();
		}
	}

	@Override
	public void onSwipeToDismissCanceled() {
		listener.setPhotoViewVisibility(pager.getCurrentItem(), true);
		if (!uiVisible) {
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
		} else if (attachments.get(currentIndex).type == Attachment.Type.VIDEO) {
			hideUiDelayed();
		}
	}

	@Override
	public void onDismissed() {
		for (ExoPlayer player : players)
			player.release();
		if (!players.isEmpty()) {
			activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		}
		listener.setPhotoViewVisibility(pager.getCurrentItem(), true);
		wm.removeView(windowView);
		listener.photoViewerDismissed();
	}

	@Override
	public void onSingleTap() {
		toggleUI();
	}

	private void toggleUI() {
		if (uiVisible) {
			uiOverlay.animate()
					.alpha(0f)
					.setDuration(250)
					.setInterpolator(CubicBezierInterpolator.DEFAULT)
					.withEndAction(new Runnable() {
						@Override
						public void run() {
							uiOverlay.setVisibility(View.GONE);
						}
					})
					.start();
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
		} else {
			uiOverlay.setVisibility(View.VISIBLE);
			uiOverlay.animate()
					.alpha(1f)
					.setDuration(300)
					.setInterpolator(CubicBezierInterpolator.DEFAULT)
					.start();
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN));
			if (attachments.get(currentIndex).type == Attachment.Type.VIDEO)
				hideUiDelayed(5000);
		}
		uiVisible = !uiVisible;
	}

	private void hideUiDelayed() {
		hideUiDelayed(2000);
	}

	private void hideUiDelayed(long delay) {
		windowView.removeCallbacks(uiAutoHider);
		windowView.postDelayed(uiAutoHider, delay);
	}

	private void onPageChanged(int index) {
		currentIndex = index;
		Attachment att = attachments.get(index);
		V.setVisibilityAnimated(videoControls, att.type == Attachment.Type.VIDEO ? View.VISIBLE : View.GONE);
		if (att.type == Attachment.Type.VIDEO) {
			videoSeekBar.setSecondaryProgress(0);
			videoDuration = (int) Math.round(att.getDuration() * 1000);
			videoLastTimeUpdatePosition = -1;
			updateVideoTimeText(0);
		}
	}

	/**
	 * 当列表滚动时同步偏移视图
	 */
	public void offsetView(float x, float y) {
		pager.setTranslationX(pager.getTranslationX() + x);
		pager.setTranslationY(pager.getTranslationY() + y);
	}

	private void incKeepScreenOn() {
		if (screenOnRefCount == 0) {
			WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			int audiofocus = GlobalUserPreferences.overlayMedia ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK : AudioManager.AUDIOFOCUS_GAIN;
			activity.getSystemService(AudioManager.class).requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, audiofocus);
		}
		screenOnRefCount++;
	}

	private void decKeepScreenOn() {
		screenOnRefCount--;
		if (screenOnRefCount < 0)
			throw new IllegalStateException();
		if (screenOnRefCount == 0) {
			WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		}
	}

	public void onPause() {
		pauseVideo();
	}

	private void shareCurrentFile() {
		Attachment att = attachments.get(pager.getCurrentItem());
		if (att.type != Attachment.Type.IMAGE) {
			shareAfterDownloading(att);
			return;
		}
		UrlImageLoaderRequest req = new UrlImageLoaderRequest(att.url);
		try {
			File file = ImageCache.getInstance(activity).getFile(req);
			if (file == null) {
				shareAfterDownloading(att);
				return;
			}
			MastodonAPIController.runInBackground(new Runnable() {
				@Override
				public void run() {
					File imageDir = new File(activity.getCacheDir(), ".");
					File renamedFile = new File(imageDir, Uri.parse(att.url).getLastPathSegment());
					file.renameTo(renamedFile);
					shareFile(renamedFile);
				}
			});
		} catch (IOException x) {
			Log.w(TAG, "shareCurrentFile: ", x);
			Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void saveCurrentFile() {
		if (Build.VERSION.SDK_INT >= 29) {
			doSaveCurrentFile();
		} else {
			if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				listener.onRequestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
			} else {
				doSaveCurrentFile();
			}
		}
	}

	/**
	 * 处理权限申请结果，由 Fragment 调用
	 */
	public void onRequestPermissionsResult(String[] permissions, int[] results) {
		if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
			doSaveCurrentFile();
		} else if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			new M3AlertDialogBuilder(activity)
					.setTitle(R.string.permission_required)
					.setMessage(R.string.storage_permission_to_download)
					.setPositiveButton(R.string.open_settings, new android.content.DialogInterface.OnClickListener() {
						@Override
						public void onClick(android.content.DialogInterface dialog, int which) {
							activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null)));
						}
					})
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	private String mimeTypeForFileName(String fileName) {
		int extOffset = fileName.lastIndexOf('.');
		if (extOffset > 0) {
			String ext = fileName.substring(extOffset + 1).toLowerCase();
			if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
			if (ext.equals("png")) return "image/png";
			if (ext.equals("gif")) return "image/gif";
			if (ext.equals("webp")) return "image/webp";
			if (ext.equals("mp4")) return "video/mp4";
			if (ext.equals("webm")) return "video/webm";
		}
		return null;
	}

	private OutputStream destinationStreamForFile(Attachment att) throws IOException {
		String fileName = Uri.parse(att.url).getLastPathSegment();
		if (Build.VERSION.SDK_INT >= 29) {
			ContentValues values = new ContentValues();
			values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
			values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
			String mime = mimeTypeForFileName(fileName);
			if (mime != null) values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
			ContentResolver cr = activity.getContentResolver();
			Uri itemUri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
			return cr.openOutputStream(itemUri);
		} else {
			return new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName));
		}
	}

	private void doSaveCurrentFile() {
		final Attachment att = attachments.get(pager.getCurrentItem());
		if (att.type == Attachment.Type.IMAGE) {
			UrlImageLoaderRequest req = new UrlImageLoaderRequest(att.url);
			try {
				final File file = ImageCache.getInstance(activity).getFile(req);
				if (file == null) {
					saveViaDownloadManager(att);
					return;
				}
				MastodonAPIController.runInBackground(new Runnable() {
					@Override
					public void run() {
						try (Source src = Okio.source(file); Sink sink = Okio.sink(destinationStreamForFile(att))) {
							BufferedSink buf = Okio.buffer(sink);
							buf.writeAll(src);
							buf.flush();
							activity.runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Toast.makeText(activity, R.string.file_saved, Toast.LENGTH_SHORT).show();
								}
							});
							if (Build.VERSION.SDK_INT < 29) {
								String fileName = Uri.parse(att.url).getLastPathSegment();
								File dstFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
								MediaScannerConnection.scanFile(activity, new String[]{dstFile.getAbsolutePath()}, new String[]{mimeTypeForFileName(fileName)}, null);
							}
						} catch (IOException x) {
							Log.w(TAG, "doSaveCurrentFile: ", x);
							activity.runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Toast.makeText(activity, R.string.error_saving_file, Toast.LENGTH_SHORT).show();
								}
							});
						}
					}
				});
			} catch (IOException x) {
				Log.w(TAG, "doSaveCurrentFile: ", x);
				Toast.makeText(activity, R.string.error_saving_file, Toast.LENGTH_SHORT).show();
			}
		} else {
			saveViaDownloadManager(att);
		}
	}

	private void saveViaDownloadManager(Attachment att) {
		Uri uri = Uri.parse(att.url);
		DownloadManager.Request req = new DownloadManager.Request(uri);
		req.allowScanningByMediaScanner();
		req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.getLastPathSegment());
		activity.getSystemService(DownloadManager.class).enqueue(req);
		Toast.makeText(activity, R.string.downloading, Toast.LENGTH_SHORT).show();
	}

	private void shareAfterDownloading(final Attachment att) {
		final Uri uri = Uri.parse(att.url);
		Toast.makeText(activity, R.string.downloading, Toast.LENGTH_SHORT).show();
		MastodonAPIController.runInBackground(new Runnable() {
			@Override
			public void run() {
				try {
					OkHttpClient client = new OkHttpClient();
					Request request = new Request.Builder().url(att.url).build();
					Response response = client.newCall(request).execute();
					if (!response.isSuccessful()) throw new IOException("" + response);
					File imageDir = new File(activity.getCacheDir(), ".");
					InputStream inputStream = response.body().byteStream();
					File file = new File(imageDir, uri.getLastPathSegment());
					FileOutputStream outputStream = new FileOutputStream(file);
					byte[] buffer = new byte[4096];
					int bytesRead;
					while ((bytesRead = inputStream.read(buffer)) != -1) {
						outputStream.write(buffer, 0, bytesRead);
					}
					outputStream.close();
					inputStream.close();
					shareFile(file);
				} catch (IOException e) {
					activity.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
						}
					});
				}
			}
		});
	}

	private void shareFile(@NonNull File file) {
		Intent intent = new Intent(Intent.ACTION_SEND);
		Uri outputUri = UiUtils.getFileProviderUri(activity, file);
		intent.setDataAndType(outputUri, mimeTypeForFileName(outputUri.getLastPathSegment()));
		intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.putExtra(Intent.EXTRA_STREAM, outputUri);
		activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.button_share)));
	}

	private void onAudioFocusChanged(int change) {
		if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
			pauseVideo();
		}
	}

	private GifVViewHolder findCurrentVideoPlayerHolder() {
		RecyclerView rv = (RecyclerView) pager.getChildAt(0);
		RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pager.getCurrentItem());
		if (vh instanceof GifVViewHolder vvh && vvh.playerReady) {
			return vvh;
		}
		return null;
	}

	private ExoPlayer findCurrentVideoPlayer() {
		GifVViewHolder holder = findCurrentVideoPlayerHolder();
		return holder != null ? holder.player : null;
	}

	private void pauseVideo() {
		GifVViewHolder holder = findCurrentVideoPlayerHolder();
		if (holder == null || holder.player == null || !holder.player.isPlaying())
			return;
		holder.player.pause();
		videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_play_24_filled);
		videoPlayPauseButton.setContentDescription(activity.getString(R.string.play));
		stopUpdatingVideoPosition();
		windowView.removeCallbacks(uiAutoHider);
		// 捕捉当前帧作为背景，防止黑屏
		Bitmap bitmap = holder.textureView.getBitmap();
		if (bitmap != null) {
			holder.wrap.setBackground(new BitmapDrawable(activity.getResources(), bitmap));
		}
	}

	private void resumeVideo() {
		ExoPlayer player = findCurrentVideoPlayer();
		if (player == null || player.isPlaying())
			return;
		player.play();
		videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_pause_24_filled);
		videoPlayPauseButton.setContentDescription(activity.getString(R.string.pause));
		startUpdatingVideoPosition(player);
	}

	private void startUpdatingVideoPosition(ExoPlayer player) {
		videoInitialPosition = (int) player.getCurrentPosition();
		videoInitialPositionTime = SystemClock.uptimeMillis();
		videoDuration = (int) player.getDuration();
		videoPositionNeedsUpdating = true;
		windowView.postOnAnimation(videoPositionUpdater);
	}

	private void stopUpdatingVideoPosition() {
		videoPositionNeedsUpdating = false;
		windowView.removeCallbacks(videoPositionUpdater);
	}

	private String formatTime(int timeSec, boolean includeHours) {
		if (includeHours)
			return String.format(Locale.getDefault(), "%d:%02d:%02d", timeSec / 3600, (timeSec % 3600) / 60, timeSec % 60);
		else
			return String.format(Locale.getDefault(), "%d:%02d", timeSec / 60, timeSec % 60);
	}

	private void updateVideoPosition() {
		if (videoPositionNeedsUpdating) {
			int currentPosition = videoInitialPosition + (int) (SystemClock.uptimeMillis() - videoInitialPositionTime);
			if (videoDuration > 0)
				videoSeekBar.setProgress(Math.round((float) currentPosition / videoDuration * 10000f));
			updateVideoTimeText(currentPosition);
			windowView.postOnAnimation(videoPositionUpdater);
		}
	}

	@SuppressLint("SetTextI18n")
	private void updateVideoTimeText(int currentPosition) {
		if (videoTimeView == null) return;
		int currentPositionSec = currentPosition / 1000;
		if (currentPositionSec != videoLastTimeUpdatePosition) {
			videoLastTimeUpdatePosition = currentPositionSec;
			boolean includeHours = videoDuration >= 3600_000;
			videoTimeView.setText(formatTime(currentPositionSec, includeHours) + " / " + formatTime(videoDuration / 1000, includeHours));
		}
	}

	private void showInfoSheet() {
		pauseVideo();
		PhotoViewerInfoSheet sheet = new PhotoViewerInfoSheet(new ContextThemeWrapper(activity, R.style.Theme_Mastodon_Dark), attachments.get(currentIndex), toolbar.getHeight(), new PhotoViewerInfoSheet.Listener() {
			private boolean ignoreBeforeDismiss;

			@Override
			public void onBeforeDismiss(int duration) {
				if (ignoreBeforeDismiss)
					return;
				if (currentSheetRelatedToolbarAnimation != null)
					currentSheetRelatedToolbarAnimation.cancel();
				AnimatorSet set = new AnimatorSet();
				set.playTogether(
						ObjectAnimator.ofFloat(pager, View.TRANSLATION_Y, 0),
						ObjectAnimator.ofFloat(toolbarWrap, View.ALPHA, 1f),
						ObjectAnimator.ofArgb(uiOverlay, STATUS_BAR_COLOR_PROPERTY, 0x80000000)
				);
				set.setDuration(duration);
				set.setInterpolator(CubicBezierInterpolator.EASE_OUT);
				currentSheetRelatedToolbarAnimation = set;
				set.addListener(new AnimatorListenerAdapter() {
					@Override
					public void onAnimationEnd(Animator animation) {
						currentSheetRelatedToolbarAnimation = null;
					}
				});
				set.start();
			}

			@Override
			public void onDismissEntireViewer() {
				ignoreBeforeDismiss = true;
				onStartSwipeToDismissTransition(0);
			}

			@Override
			public void onButtonClick(int id) {
				if (id == R.id.btn_boost && status != null) {
					AccountSessionManager.get(accountID).getStatusInteractionController().setReblogged(status, !status.reblogged, null, r -> {});
				} else if (id == R.id.btn_favorite && status != null) {
					AccountSessionManager.get(accountID).getStatusInteractionController().setFavorited(status, !status.favourited, r -> {});
				} else if (id == R.id.btn_bookmark && status != null) {
					AccountSessionManager.get(accountID).getStatusInteractionController().setBookmarked(status, !status.bookmarked);
				}
			}
		});
		sheet.setStatus(status);
		sheet.show();
		if (currentSheetRelatedToolbarAnimation != null)
			currentSheetRelatedToolbarAnimation.cancel();
		sheet.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
			@Override
			public boolean onPreDraw() {
				sheet.getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(this);
				AnimatorSet set = new AnimatorSet();
				set.playTogether(
						ObjectAnimator.ofFloat(pager, View.TRANSLATION_Y, -pager.getHeight() * 0.2f),
						ObjectAnimator.ofFloat(toolbarWrap, View.ALPHA, 0f),
						ObjectAnimator.ofArgb(uiOverlay, STATUS_BAR_COLOR_PROPERTY, 0)
				);
				set.setDuration(300);
				set.setInterpolator(CubicBezierInterpolator.DEFAULT);
				currentSheetRelatedToolbarAnimation = set;
				set.addListener(new AnimatorListenerAdapter() {
					@Override
					public void onAnimationEnd(Animator animation) {
						currentSheetRelatedToolbarAnimation = null;
					}
				});
				set.start();
				return true;
			}
		});
	}

	public interface Listener {
		void setPhotoViewVisibility(int index, boolean visible);
		boolean startPhotoViewTransition(int index, @NonNull Rect outRect, @NonNull int[] outCornerRadius);
		void setTransitioningViewTransform(float translateX, float translateY, float scale);
		void endPhotoViewTransition();
		@Nullable Drawable getPhotoViewCurrentDrawable(int index);
		void photoViewerDismissed();
		void onRequestPermissions(String[] permissions);
	}

	private class PhotoViewAdapter extends RecyclerView.Adapter<BaseHolder> {
		@NonNull
		@Override
		public BaseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if (viewType == 0) return new PhotoViewHolder();
			return new GifVViewHolder();
		}

		@Override
		public void onBindViewHolder(@NonNull BaseHolder holder, int position) {
			holder.bind(attachments.get(position));
		}

		@Override
		public int getItemCount() {
			return attachments.size();
		}

		@Override
		public int getItemViewType(int position) {
			Attachment att = attachments.get(position);
			if (att.type == Attachment.Type.IMAGE) return 0;
			return 1;
		}

		@Override
		public void onViewDetachedFromWindow(@NonNull BaseHolder holder) {
			super.onViewDetachedFromWindow(holder);
			if (holder instanceof GifVViewHolder gifHolder) {
				gifHolder.reset();
			}
		}

		@Override
		public void onViewAttachedToWindow(@NonNull BaseHolder holder) {
			super.onViewAttachedToWindow(holder);
			if (holder instanceof GifVViewHolder gifHolder) {
				gifHolder.prepareAndStartPlayer();
			}
		}
	}

	private abstract class BaseHolder extends BindableViewHolder<Attachment> {
		public ZoomPanView zoomPanView;

		public BaseHolder() {
			super(new ZoomPanView(activity));
			zoomPanView = (ZoomPanView) itemView;
			zoomPanView.setListener(PhotoViewer.this);
			zoomPanView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		}

		@Override
		public void onBind(Attachment item) {
			zoomPanView.setScrollDirections(getAbsoluteAdapterPosition() > 0, getAbsoluteAdapterPosition() < attachments.size() - 1);
		}
	}

	private class PhotoViewHolder extends BaseHolder implements ViewImageLoader.Target {
		public ImageView imageView;

		public PhotoViewHolder() {
			imageView = new ImageView(activity);
			zoomPanView.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		}

		@Override
		public void onBind(Attachment item) {
			super.onBind(item);
			FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) imageView.getLayoutParams();
			Drawable currentDrawable = listener.getPhotoViewCurrentDrawable(getAbsoluteAdapterPosition());
			if (item.hasKnownDimensions()) {
				params.width = item.getWidth();
				params.height = item.getHeight();
			} else if (currentDrawable != null) {
				params.width = currentDrawable.getIntrinsicWidth();
				params.height = currentDrawable.getIntrinsicHeight();
			} else {
				params.width = 1920;
				params.height = 1080;
			}
			ViewImageLoader.load(this, currentDrawable, new UrlImageLoaderRequest(item.url), false);
		}

		@Override
		public void setImageDrawable(Drawable d) {
			imageView.setImageDrawable(d);
		}

		@Override
		public View getView() {
			return imageView;
		}
	}

	private class GifVViewHolder extends BaseHolder implements Player.Listener, TextureView.SurfaceTextureListener {
		public TextureView textureView;
		public FrameLayout wrap;
		public ExoPlayer player;
		private Surface surface;
		private boolean playerReady;
		private boolean keepingScreenOn;
		private ProgressBar progressBar;

		public GifVViewHolder() {
			textureView = new TextureView(activity);
			wrap = new FrameLayout(activity);
			zoomPanView.addView(wrap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
			wrap.addView(textureView);

			progressBar = new ProgressBar(activity);
			progressBar.setIndeterminateTintList(ColorStateList.valueOf(0xffffffff));
			zoomPanView.addView(progressBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

			textureView.setSurfaceTextureListener(this);
		}

		@Override
		public void onBind(Attachment item) {
			super.onBind(item);
			playerReady = false;
			FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wrap.getLayoutParams();
			Drawable currentDrawable = listener.getPhotoViewCurrentDrawable(getAbsoluteAdapterPosition());
			if (item.hasKnownDimensions()) {
				params.width = item.getWidth();
				params.height = item.getHeight();
			} else if (currentDrawable != null) {
				params.width = currentDrawable.getIntrinsicWidth();
				params.height = currentDrawable.getIntrinsicHeight();
			} else {
				params.width = 1920;
				params.height = 1080;
			}
			wrap.setBackground(currentDrawable);
			progressBar.setVisibility(item.type == Attachment.Type.VIDEO ? View.VISIBLE : View.GONE);
			if (itemView.isAttachedToWindow()) {
				reset();
				prepareAndStartPlayer();
			}
		}

		public void prepareAndStartPlayer() {
			if (player != null) return;
			playerReady = false;
			// 解决播放慢：使用 OkHttpDataSource 并设置全局客户端
			OkHttpDataSource.Factory dataSourceFactory = new OkHttpDataSource.Factory(new okhttp3.OkHttpClient());
			player = new ExoPlayer.Builder(activity)
					.setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
					.build();
			players.add(player);
			player.addListener(this);
			
			MediaItem mediaItem = MediaItem.fromUri(item.url);
			player.setMediaItem(mediaItem);
			if (surface != null) player.setVideoSurface(surface);
			player.prepare();
			
			if (item.type == Attachment.Type.VIDEO) {
				player.setRepeatMode(Player.REPEAT_MODE_OFF);
			} else {
				player.setRepeatMode(Player.REPEAT_MODE_ALL);
			}
			player.play();
		}

		public void reset() {
			playerReady = false;
			if (player != null) {
				player.stop();
				player.release();
				players.remove(player);
				player = null;
			}
			if (keepingScreenOn) {
				decKeepScreenOn();
				keepingScreenOn = false;
			}
		}

		@Override
		public void onPlaybackStateChanged(int state) {
			if (state == Player.STATE_READY) {
				playerReady = true;
				progressBar.setVisibility(View.GONE);
				if (item.type == Attachment.Type.VIDEO) {
					incKeepScreenOn();
					keepingScreenOn = true;
					if (getAbsoluteAdapterPosition() == currentIndex) {
						startUpdatingVideoPosition(player);
						hideUiDelayed();
					}
				}
			} else if (state == Player.STATE_BUFFERING) {
				progressBar.setVisibility(View.VISIBLE);
				if (item.type == Attachment.Type.VIDEO) stopUpdatingVideoPosition();
			} else if (state == Player.STATE_ENDED) {
				onCompletion();
			}
		}

		@Override
		public void onPlayerError(@NonNull PlaybackException error) {
			Log.e(TAG, "ExoPlayer error: ", error);
			Toast.makeText(activity, R.string.error_playing_video, Toast.LENGTH_SHORT).show();
			onStartSwipeToDismissTransition(0f);
		}

		@Override
		public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
			if (videoSize.width <= 0 || videoSize.height <= 0) return;
			FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) wrap.getLayoutParams();
			params.width = videoSize.width;
			params.height = videoSize.height;
			zoomPanView.updateLayout();
		}

		private void onCompletion() {
			videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_play_24_filled);
			videoPlayPauseButton.setContentDescription(activity.getString(R.string.play));
			stopUpdatingVideoPosition();
			if (!uiVisible) toggleUI();
			windowView.removeCallbacks(uiAutoHider);
		}

		@Override
		public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
			this.surface = new Surface(surfaceTexture);
			if (player != null) player.setVideoSurface(this.surface);
		}

		@Override
		public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {}

		@Override
		public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
			if (this.surface != null) {
				this.surface.release();
				this.surface = null;
			}
			return true;
		}

		@Override
		public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
			// 新帧渲染，移除背景占位图
			if (player != null && player.isPlaying() && wrap.getBackground() != null) {
				wrap.setBackground(null);
			}
		}
	}
}
