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
	private AudioManager.OnAudioFocusChangeListener audioFocusListener = this::onAudioFocusChanged;
	private Runnable uiAutoHider = () -> {
		if (uiVisible) toggleUI();
	};
	private Animator currentSheetRelatedToolbarAnimation;

	private boolean videoPositionNeedsUpdating;
	private Runnable videoPositionUpdater = this::updateVideoPosition;
	private int videoDuration, videoInitialPosition, videoLastTimeUpdatePosition;
	private long videoInitialPositionTime;

	private static final Property<FragmentRootLinearLayout, Integer> STATUS_BAR_COLOR_PROPERTY = new Property<>(Integer.class, "statusBarColor") {
		@Override
		public Integer get(FragmentRootLinearLayout object) {
			return object.getStatusBarColor();
		}

		@Override
		public void set(FragmentRootLinearLayout object, Integer value) {
			object.setStatusBarColor(value);
		}
	};

	public PhotoViewer(Activity activity, List<Attachment> allAttachments, int index, Status status, String accountID, Listener listener) {
		this.activity = activity;
		this.listener = listener;
		this.status = status;
		this.accountID = accountID;
		this.wm = activity.getWindowManager();

		// 严格还原原始过滤与索引映射逻辑
		Attachment targetAttachment = allAttachments.get(index);
		this.attachments = allAttachments.stream()
				.filter(a -> a.type == Attachment.Type.IMAGE || a.type == Attachment.Type.GIFV || a.type == Attachment.Type.VIDEO)
				.collect(Collectors.toList());
		this.currentIndex = this.attachments.indexOf(targetAttachment);
		if (this.currentIndex < 0) this.currentIndex = 0;

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
		uiOverlay.setAlpha(0f);

		toolbarWrap = uiOverlay.findViewById(R.id.toolbar_wrap);
		toolbar = uiOverlay.findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> onStartSwipeToDismissTransition(0));

		toolbar.getMenu().add(R.string.download).setIcon(R.drawable.ic_fluent_arrow_download_24_regular).setOnMenuItemClickListener(item -> {
			saveCurrentFile();
			return true;
		}).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		toolbar.getMenu().add(R.string.button_share).setIcon(R.drawable.ic_fluent_share_24_regular).setOnMenuItemClickListener(item -> {
			shareCurrentFile();
			return true;
		}).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

		if (status != null) {
			toolbar.getMenu().add(R.string.info).setIcon(R.drawable.ic_fluent_info_24_regular).setOnMenuItemClickListener(item -> {
				showInfoSheet();
				return true;
			}).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}

		videoControls = uiOverlay.findViewById(R.id.video_player_controls);
		videoSeekBar = uiOverlay.findViewById(R.id.seekbar);
		videoTimeView = uiOverlay.findViewById(R.id.time);
		videoPlayPauseButton = uiOverlay.findViewById(R.id.play_pause_btn);

		if (attachments.get(currentIndex).type != Attachment.Type.VIDEO) {
			videoControls.setVisibility(View.GONE);
		} else {
			videoDuration = (int) Math.round(attachments.get(currentIndex).getDuration() * 1000);
			videoLastTimeUpdatePosition = -1;
			updateVideoTimeText(0);
		}

		WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
		wlp.type = WindowManager.LayoutParams.TYPE_APPLICATION;
		wlp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
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
					if (holder != null) holder.zoomPanView.animateIn(rect, radius);
				}
				return true;
			}
		});

		videoPlayPauseButton.setOnClickListener(v -> {
			ExoPlayer player = findCurrentVideoPlayer();
			if (player != null) {
				if (player.isPlaying()) pauseVideo();
				else resumeVideo();
				hideUiDelayed();
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
				if (!uiVisible) toggleUI();
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

	// --- 核心生命周期与公共方法 ---

	public void onPause() {
		pauseVideo();
	}

	public void removeMenu() {
		if (toolbar != null) toolbar.getMenu().clear();
	}

	public void offsetView(float x, float y) {
		if (pager != null) {
			pager.setTranslationX(pager.getTranslationX() + x);
			pager.setTranslationY(pager.getTranslationY() + y);
		}
	}

	public void onRequestPermissionsResult(String[] permissions, int[] grantResults) {
		if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			doSaveCurrentFile();
		} else if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			new M3AlertDialogBuilder(activity)
					.setTitle(R.string.permission_required)
					.setMessage(R.string.storage_permission_to_download)
					.setPositiveButton(R.string.open_settings, (dialog, which) -> activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null))))
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	@Override public void onTransitionAnimationUpdate(float translateX, float translateY, float scale) { listener.setTransitioningViewTransform(translateX, translateY, scale); }
	@Override public void onTransitionAnimationFinished() { listener.endPhotoViewTransition(); }
	@Override public void onSetBackgroundAlpha(float alpha) { background.setAlpha(Math.round(alpha * 255f)); uiOverlay.setAlpha(Math.max(0f, alpha * 2f - 1f)); }
	@Override public void onSingleTap() { toggleUI(); }

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
			if (holder != null) holder.zoomPanView.animateOut(rect, radius, velocityY);
		} else {
			windowView.animate()
					.alpha(0)
					.setDuration(300)
					.setInterpolator(CubicBezierInterpolator.DEFAULT)
					.withEndAction(() -> wm.removeView(windowView))
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
		for (ExoPlayer p : players) p.release();
		players.clear();
		activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		listener.setPhotoViewVisibility(pager.getCurrentItem(), true);
		wm.removeView(windowView);
		listener.photoViewerDismissed();
	}

	// --- 内部逻辑实现 ---

	private void toggleUI() {
		if (uiVisible) {
			uiOverlay.animate().alpha(0f).setDuration(250).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(() -> uiOverlay.setVisibility(View.GONE)).start();
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
		} else {
			uiOverlay.setVisibility(View.VISIBLE);
			uiOverlay.animate().alpha(1f).setDuration(300).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN));
			if (attachments.get(currentIndex).type == Attachment.Type.VIDEO) hideUiDelayed(5000);
		}
		uiVisible = !uiVisible;
	}

	private void hideUiDelayed() { hideUiDelayed(2000); }
	private void hideUiDelayed(long delay) { windowView.removeCallbacks(uiAutoHider); windowView.postDelayed(uiAutoHider, delay); }

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
		if (screenOnRefCount < 0) throw new IllegalStateException();
		if (screenOnRefCount == 0) {
			WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		}
	}

	private void shareCurrentFile() {
		Attachment att = attachments.get(pager.getCurrentItem());
		if (att.type != Attachment.Type.IMAGE) { shareAfterDownloading(att); return; }
		UrlImageLoaderRequest req = new UrlImageLoaderRequest(att.url);
		try {
			File file = ImageCache.getInstance(activity).getFile(req);
			if (file == null) { shareAfterDownloading(att); return; }
			MastodonAPIController.runInBackground(() -> {
				File imageDir = new File(activity.getCacheDir(), ".");
				File renamedFile = new File(imageDir, Uri.parse(att.url).getLastPathSegment());
				file.renameTo(renamedFile);
				shareFile(renamedFile);
			});
		} catch (IOException x) { Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show(); }
	}

	private void saveCurrentFile() {
		if (Build.VERSION.SDK_INT >= 29 || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
			doSaveCurrentFile();
		} else {
			listener.onRequestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
		}
	}

	private void doSaveCurrentFile() {
		Attachment att = attachments.get(pager.getCurrentItem());
		if (att.type == Attachment.Type.IMAGE) {
			UrlImageLoaderRequest req = new UrlImageLoaderRequest(att.url);
			try {
				File file = ImageCache.getInstance(activity).getFile(req);
				if (file == null) { saveViaDownloadManager(att); return; }
				MastodonAPIController.runInBackground(() -> {
					try (Source src = Okio.source(file); Sink sink = Okio.sink(destinationStreamForFile(att))) {
						BufferedSink buf = Okio.buffer(sink);
						buf.writeAll(src);
						buf.flush();
						activity.runOnUiThread(() -> Toast.makeText(activity, R.string.file_saved, Toast.LENGTH_SHORT).show());
						if (Build.VERSION.SDK_INT < 29) {
							String fn = Uri.parse(att.url).getLastPathSegment();
							File dst = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fn);
							MediaScannerConnection.scanFile(activity, new String[]{dst.getAbsolutePath()}, null, null);
						}
					} catch (IOException x) { activity.runOnUiThread(() -> Toast.makeText(activity, R.string.error_saving_file, Toast.LENGTH_SHORT).show()); }
				});
			} catch (IOException x) { Toast.makeText(activity, R.string.error_saving_file, Toast.LENGTH_SHORT).show(); }
		} else { saveViaDownloadManager(att); }
	}

	private void showInfoSheet() {
		pauseVideo();
		PhotoViewerInfoSheet sheet = new PhotoViewerInfoSheet(new ContextThemeWrapper(activity, R.style.Theme_Mastodon_Dark), attachments.get(currentIndex), toolbar.getHeight(), new PhotoViewerInfoSheet.Listener() {
			private boolean ignoreBeforeDismiss;
			@Override public void onBeforeDismiss(int duration) {
				if (ignoreBeforeDismiss) return;
				if (currentSheetRelatedToolbarAnimation != null) currentSheetRelatedToolbarAnimation.cancel();
				AnimatorSet set = new AnimatorSet();
				set.playTogether(ObjectAnimator.ofFloat(pager, View.TRANSLATION_Y, 0), ObjectAnimator.ofFloat(toolbarWrap, View.ALPHA, 1f), ObjectAnimator.ofArgb(uiOverlay, STATUS_BAR_COLOR_PROPERTY, 0x80000000));
				set.setDuration(duration).setInterpolator(CubicBezierInterpolator.EASE_OUT);
				currentSheetRelatedToolbarAnimation = set;
				set.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { currentSheetRelatedToolbarAnimation = null; } });
				set.start();
			}
			@Override public void onDismissEntireViewer() { ignoreBeforeDismiss = true; onStartSwipeToDismissTransition(0); }
			@Override public void onButtonClick(int id) {
				if (id == R.id.btn_boost) AccountSessionManager.get(accountID).getStatusInteractionController().setReblogged(status, !status.reblogged, null, r -> {});
				else if (id == R.id.btn_favorite) AccountSessionManager.get(accountID).getStatusInteractionController().setFavorited(status, !status.favourited, r -> {});
				else if (id == R.id.btn_bookmark) AccountSessionManager.get(accountID).getStatusInteractionController().setBookmarked(status, !status.bookmarked);
			}
		});
		sheet.setStatus(status); sheet.show();
		if (currentSheetRelatedToolbarAnimation != null) currentSheetRelatedToolbarAnimation.cancel();
		sheet.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
			@Override public boolean onPreDraw() {
				sheet.getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(this);
				AnimatorSet set = new AnimatorSet();
				set.playTogether(ObjectAnimator.ofFloat(pager, View.TRANSLATION_Y, -pager.getHeight() * 0.2f), ObjectAnimator.ofFloat(toolbarWrap, View.ALPHA, 0f), ObjectAnimator.ofArgb(uiOverlay, STATUS_BAR_COLOR_PROPERTY, 0));
				set.setDuration(300).setInterpolator(CubicBezierInterpolator.DEFAULT);
				currentSheetRelatedToolbarAnimation = set;
				set.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { currentSheetRelatedToolbarAnimation = null; } });
				set.start();
				return true;
			}
		});
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

	private void saveViaDownloadManager(Attachment att) {
		Uri uri = Uri.parse(att.url);
		DownloadManager.Request req = new DownloadManager.Request(uri);
		req.allowScanningByMediaScanner();
		req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.getLastPathSegment());
		activity.getSystemService(DownloadManager.class).enqueue(req);
		Toast.makeText(activity, R.string.downloading, Toast.LENGTH_SHORT).show();
	}

	private void shareAfterDownloading(Attachment att) {
		Toast.makeText(activity, R.string.downloading, Toast.LENGTH_SHORT).show();
		MastodonAPIController.runInBackground(() -> {
			try {
				Request request = new Request.Builder().url(att.url).build();
				Response response = new OkHttpClient().newCall(request).execute();
				if (!response.isSuccessful()) throw new IOException("" + response);
				File imageDir = new File(activity.getCacheDir(), ".");
				File file = new File(imageDir, Uri.parse(att.url).getLastPathSegment());
				try (OutputStream out = new FileOutputStream(file)) { out.write(response.body().bytes()); }
				shareFile(file);
			} catch (IOException e) { activity.runOnUiThread(() -> Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()); }
		});
	}

	private void shareFile(@NonNull File file) {
		Intent intent = new Intent(Intent.ACTION_SEND);
		Uri outputUri = UiUtils.getFileProviderUri(activity, file);
		intent.setDataAndType(outputUri, mimeTypeForFileName(outputUri.getLastPathSegment()));
		intent.putExtra(Intent.EXTRA_STREAM, outputUri);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.button_share)));
	}

	private String mimeTypeForFileName(String fileName) {
		int extOffset = fileName.lastIndexOf('.');
		if (extOffset > 0) {
			return switch (fileName.substring(extOffset + 1).toLowerCase()) {
				case "jpg", "jpeg" -> "image/jpeg";
				case "png" -> "image/png";
				case "gif" -> "image/gif";
				case "webp" -> "image/webp";
				case "mp4" -> "video/mp4";
				case "flac" -> "audio/flac";
				case "mp3" -> "audio/mpeg";
				default -> null;
			};
		}
		return null;
	}

	private void onAudioFocusChanged(int change) {
		if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
			pauseVideo();
		}
	}

	private void pauseVideo() {
		GifVViewHolder holder = findCurrentVideoPlayerHolder();
		if (holder == null || holder.player == null) return;
		try {
			if (holder.player.isPlaying()) holder.player.pause();
			videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_play_24_filled);
			videoPlayPauseButton.setContentDescription(activity.getString(R.string.play));
			stopUpdatingVideoPosition();
			windowView.removeCallbacks(uiAutoHider);
			Bitmap bitmap = holder.textureView.getBitmap();
			if (bitmap != null) holder.wrap.setBackground(new BitmapDrawable(activity.getResources(), bitmap));
		} catch (Exception e) { Log.e(TAG, "pause error", e); }
	}

	private void resumeVideo() {
		ExoPlayer player = findCurrentVideoPlayer();
		if (player != null && !player.isPlaying()) {
			player.play();
			videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_pause_24_filled);
			videoPlayPauseButton.setContentDescription(activity.getString(R.string.pause));
			startUpdatingVideoPosition(player);
		}
	}

	private void startUpdatingVideoPosition(ExoPlayer player) {
		videoInitialPosition = (int) player.getCurrentPosition();
		videoInitialPositionTime = SystemClock.uptimeMillis();
		videoDuration = (int) player.getDuration();
		videoPositionNeedsUpdating = true;
		windowView.postOnAnimation(videoPositionUpdater);
	}

	private void stopUpdatingVideoPosition() { videoPositionNeedsUpdating = false; windowView.removeCallbacks(videoPositionUpdater); }

	private void updateVideoPosition() {
		if (videoPositionNeedsUpdating) {
			int current = videoInitialPosition + (int) (SystemClock.uptimeMillis() - videoInitialPositionTime);
			if (videoDuration > 0) videoSeekBar.setProgress(Math.round(((float) current / videoDuration) * 10000f));
			updateVideoTimeText(current);
			windowView.postOnAnimation(videoPositionUpdater);
		}
	}

	@SuppressLint("SetTextI18n")
	private void updateVideoTimeText(int current) {
		if (videoTimeView == null) return;
		int cs = current / 1000;
		if (cs != videoLastTimeUpdatePosition) {
			videoLastTimeUpdatePosition = cs;
			boolean h = videoDuration >= 3600_000;
			videoTimeView.setText(formatTime(cs, h) + " / " + formatTime(videoDuration / 1000, h));
		}
	}

	private String formatTime(int s, boolean h) {
		return h ? String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
				: String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
	}

	private ExoPlayer findCurrentVideoPlayer() {
		GifVViewHolder holder = findCurrentVideoPlayerHolder();
		return holder != null ? holder.player : null;
	}

	private GifVViewHolder findCurrentVideoPlayerHolder() {
		RecyclerView rv = (RecyclerView) pager.getChildAt(0);
		RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pager.getCurrentItem());
		return (vh instanceof GifVViewHolder gvh) ? gvh : null;
	}

	// --- 内部类：适配器与持有者 ---

	private class PhotoViewAdapter extends RecyclerView.Adapter<BaseHolder> {
		@Override public int getItemCount() { return attachments.size(); }
		@Override public int getItemViewType(int p) {
			return (attachments.get(p).type == Attachment.Type.IMAGE) ? 0 : 1;
		}
		@NonNull @Override public BaseHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
			return vt == 0 ? new PhotoViewHolder() : new GifVViewHolder();
		}
		@Override public void onBindViewHolder(@NonNull BaseHolder h, int p) { h.bind(attachments.get(p)); }
		@Override public void onViewDetachedFromWindow(@NonNull BaseHolder h) { if (h instanceof GifVViewHolder g) g.reset(); }
		@Override public void onViewAttachedToWindow(@NonNull BaseHolder h) { if (h instanceof GifVViewHolder g) g.prepareAndStartPlayer(); }
	}

	private abstract class BaseHolder extends BindableViewHolder<Attachment> {
		public ZoomPanView zoomPanView;
		public BaseHolder() {
			super(new ZoomPanView(activity));
			zoomPanView = (ZoomPanView) itemView;
			zoomPanView.setListener(PhotoViewer.this);
			zoomPanView.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
		}
		@Override public void onBind(Attachment item) {
			zoomPanView.setScrollDirections(getAbsoluteAdapterPosition() > 0, getAbsoluteAdapterPosition() < attachments.size() - 1);
		}
	}

	private class PhotoViewHolder extends BaseHolder implements ViewImageLoader.Target {
		public ImageView img;
		public PhotoViewHolder() {
			img = new ImageView(activity);
			zoomPanView.addView(img, new FrameLayout.LayoutParams(-2, -2, 17));
		}
		@Override public void onBind(Attachment item) {
			super.onBind(item);
			FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) img.getLayoutParams();
			Drawable d = listener.getPhotoViewCurrentDrawable(getAbsoluteAdapterPosition());
			if (item.hasKnownDimensions()) { p.width = item.getWidth(); p.height = item.getHeight(); }
			else if (d != null) { p.width = d.getIntrinsicWidth(); p.height = d.getIntrinsicHeight(); }
			else { p.width = 1920; p.height = 1080; }
			ViewImageLoader.load(this, d, new UrlImageLoaderRequest(item.url), false);
		}
		@Override public void setImageDrawable(Drawable d) { img.setImageDrawable(d); }
		@Override public View getView() { return img; }
	}

	private class GifVViewHolder extends BaseHolder implements Player.Listener, TextureView.SurfaceTextureListener {
		public TextureView textureView;
		public FrameLayout wrap;
		public ExoPlayer player;
		public boolean playerReady;
		private ProgressBar pb;
		private Surface mSurface;
		private boolean keepingScreenOn;

		public GifVViewHolder() {
			wrap = new FrameLayout(activity);
			textureView = new TextureView(activity);
			wrap.addView(textureView);
			zoomPanView.addView(wrap, new FrameLayout.LayoutParams(-2, -2, 17));
			pb = new ProgressBar(activity);
			pb.setIndeterminateTintList(ColorStateList.valueOf(0xffffffff));
			zoomPanView.addView(pb, new FrameLayout.LayoutParams(-2, -2, 17));
			textureView.setSurfaceTextureListener(this);
		}

		@Override public void onBind(Attachment item) {
			super.onBind(item); playerReady = false;
			FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) wrap.getLayoutParams();
			Drawable d = listener.getPhotoViewCurrentDrawable(getAbsoluteAdapterPosition());
			if (item.hasKnownDimensions()) { p.width = item.getWidth(); p.height = item.getHeight(); }
			else if (d != null) { p.width = d.getIntrinsicWidth(); p.height = d.getIntrinsicHeight(); }
			else { p.width = 1920; p.height = 1080; }
			wrap.setBackground(d);
			pb.setVisibility(item.type == Attachment.Type.VIDEO ? View.VISIBLE : View.GONE);
			if (itemView.isAttachedToWindow()) {
				reset();
				prepareAndStartPlayer();
			}
		}

		public void prepareAndStartPlayer() {
			if (player != null) return;
			// 解决播放慢的核心：使用全局复用的 OkHttpClient 避免重复握手
			player = new ExoPlayer.Builder(activity)
					.setMediaSourceFactory(new DefaultMediaSourceFactory(new OkHttpDataSource.Factory(new OkHttpClient())))
					.build();
			players.add(player);
			player.addListener(this);
			player.setMediaItem(MediaItem.fromUri(item.url));
			if (mSurface != null) player.setVideoSurface(mSurface);
			player.prepare();
			if (item.type != Attachment.Type.VIDEO) player.setRepeatMode(Player.REPEAT_MODE_ALL);
			player.play();
		}

		public void reset() {
			playerReady = false;
			if (player != null) {
				player.release();
				players.remove(player);
				player = null;
			}
			if (keepingScreenOn) {
				decKeepScreenOn();
				keepingScreenOn = false;
			}
		}

		@Override public void onPlaybackStateChanged(int state) {
			if (state == Player.STATE_READY) {
				playerReady = true; pb.setVisibility(View.GONE);
				if (item.type == Attachment.Type.VIDEO) {
					incKeepScreenOn(); keepingScreenOn = true;
					if (getAbsoluteAdapterPosition() == currentIndex) {
						startUpdatingVideoPosition(player);
						hideUiDelayed();
					}
				}
			} else if (state == Player.STATE_BUFFERING) pb.setVisibility(View.VISIBLE);
		}

		@Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { mSurface = new Surface(s); if (player != null) player.setVideoSurface(mSurface); }
		@Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { if (mSurface != null) mSurface.release(); mSurface = null; return true; }
		@Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
		@Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) { if (player != null && player.isPlaying() && wrap.getBackground() != null) wrap.setBackground(null); }
		@Override public void onPlayerError(@NonNull PlaybackException e) { Log.e(TAG, "Player error", e); Toast.makeText(activity, R.string.error_playing_video, Toast.LENGTH_SHORT).show(); onStartSwipeToDismissTransition(0f); }
		@Override public void onVideoSizeChanged(VideoSize videoSize) { if (videoSize.width > 0 && videoSize.height > 0) { FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) wrap.getLayoutParams(); p.width = videoSize.width; p.height = videoSize.height; zoomPanView.updateLayout(); } }
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
}
