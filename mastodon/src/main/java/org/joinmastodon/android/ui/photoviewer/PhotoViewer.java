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
		public Integer get(FragmentRootLinearLayout object) { return object.getStatusBarColor(); }
		@Override
		public void set(FragmentRootLinearLayout object, Integer value) { object.setStatusBarColor(value); }
	};

	public PhotoViewer(Activity activity, List<Attachment> attachments, int index, Status status, String accountID, Listener listener) {
		this.activity = activity;
		this.attachments = attachments.stream().filter(a -> a.type == Attachment.Type.IMAGE || a.type == Attachment.Type.GIFV || a.type == Attachment.Type.VIDEO).collect(Collectors.toList());
		this.currentIndex = index;
		this.listener = listener;
		this.status = status;
		this.accountID = accountID;
		wm = activity.getWindowManager();

		windowView = new FrameLayout(activity) {
			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
					onStartSwipeToDismissTransition(0f);
					return true;
				}
				return super.dispatchKeyEvent(event);
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
					}
				}
				uiOverlay.dispatchApplyWindowInsets(insets);
				return insets.consumeSystemWindowInsets();
			}
		};

		windowView.setBackground(background);
		pager = new ViewPager2(activity);
		pager.setAdapter(new PhotoViewAdapter());
		pager.setCurrentItem(index, false);
		pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
			@Override
			public void onPageSelected(int position) { onPageChanged(position); }
		});
		windowView.addView(pager);

		uiOverlay = (FragmentRootLinearLayout) activity.getLayoutInflater().inflate(R.layout.photo_viewer_ui, windowView, false);
		windowView.addView(uiOverlay);
		toolbarWrap = uiOverlay.findViewById(R.id.toolbar_wrap);
		toolbar = uiOverlay.findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> onStartSwipeToDismissTransition(0));
		
		toolbar.getMenu().add(R.string.download).setIcon(R.drawable.ic_fluent_arrow_download_24_regular).setOnMenuItemClickListener(item -> { saveCurrentFile(); return true; }).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		toolbar.getMenu().add(R.string.button_share).setIcon(R.drawable.ic_fluent_share_24_regular).setOnMenuItemClickListener(item -> { shareCurrentFile(); return true; }).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

		if(status != null){
			toolbar.getMenu().add(R.string.info).setIcon(R.drawable.ic_fluent_info_24_regular).setOnMenuItemClickListener(item->{ showInfoSheet(); return true; }).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}

		videoControls = uiOverlay.findViewById(R.id.video_player_controls);
		videoSeekBar = uiOverlay.findViewById(R.id.seekbar);
		videoTimeView = uiOverlay.findViewById(R.id.time);
		videoPlayPauseButton = uiOverlay.findViewById(R.id.play_pause_btn);

		if(this.attachments.get(index).type != Attachment.Type.VIDEO){
			videoControls.setVisibility(View.GONE);
		}else{
			videoDuration = (int)Math.round(this.attachments.get(index).getDuration() * 1000);
			updateVideoTimeText(0);
		}

		WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
		wlp.type = WindowManager.LayoutParams.TYPE_APPLICATION;
		wlp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
		wlp.format = PixelFormat.TRANSLUCENT;
		wm.addView(windowView, wlp);

		videoPlayPauseButton.setOnClickListener(v -> {
			ExoPlayer player = findCurrentVideoPlayer();
			if (player != null) {
				if (player.isPlaying()) pauseVideo(); else resumeVideo();
				hideUiDelayed();
			}
		});

		videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) updateVideoTimeText(Math.round((progress / 10000f) * videoDuration));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) { stopUpdatingVideoPosition(); }
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				ExoPlayer player = findCurrentVideoPlayer();
				if (player != null) player.seekTo((long) ((seekBar.getProgress() / 10000f) * player.getDuration()));
				hideUiDelayed();
			}
		});
	}

	public void onRequestPermissionsResult(String[] permissions, int[] grantResults) {
		if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			doSaveCurrentFile();
		} else if(!activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)){
			new M3AlertDialogBuilder(activity)
					.setTitle(R.string.permission_required)
					.setMessage(R.string.storage_permission_to_download)
					.setPositiveButton(R.string.open_settings, (dialog, which)->activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null))))
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	public void offsetView(float x, float y) {
		pager.setTranslationX(pager.getTranslationX() + x);
		pager.setTranslationY(pager.getTranslationY() + y);
	}

	public void removeMenu() { if (toolbar != null) toolbar.getMenu().clear(); }

	private ExoPlayer findCurrentVideoPlayer() {
		GifVViewHolder holder = findCurrentVideoPlayerHolder();
		return holder != null ? holder.player : null;
	}

	private void pauseVideo() {
		GifVViewHolder holder = findCurrentVideoPlayerHolder();
		if (holder == null || holder.player == null) return;
		try {
			if (holder.player.isPlaying()) holder.player.pause();
			videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_play_24_filled);
			stopUpdatingVideoPosition();
			windowView.removeCallbacks(uiAutoHider);
			Bitmap bitmap = holder.textureView.getBitmap();
			if (bitmap != null) holder.wrap.setBackground(new BitmapDrawable(activity.getResources(), bitmap));
		} catch (Exception e) { Log.e(TAG, "pause error", e); }
	}

	private void resumeVideo() {
		ExoPlayer player = findCurrentVideoPlayer();
		if (player != null) {
			player.play();
			videoPlayPauseButton.setImageResource(R.drawable.ic_fluent_pause_24_filled);
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

	private void stopUpdatingVideoPosition() {
		videoPositionNeedsUpdating = false;
		windowView.removeCallbacks(videoPositionUpdater);
	}

	private void updateVideoPosition() {
		if (videoPositionNeedsUpdating) {
			int current = videoInitialPosition + (int) (SystemClock.uptimeMillis() - videoInitialPositionTime);
			videoSeekBar.setProgress(Math.round(((float) current / videoDuration) * 10000f));
			updateVideoTimeText(current);
			windowView.postOnAnimation(videoPositionUpdater);
		}
	}

	@SuppressLint("SetTextI18n")
	private void updateVideoTimeText(int current) {
		int currentSec = current / 1000;
		if (currentSec != videoLastTimeUpdatePosition) {
			videoLastTimeUpdatePosition = currentSec;
			boolean includeHours = videoDuration >= 3600_000;
			videoTimeView.setText(formatTime(currentSec, includeHours) + " / " + formatTime(videoDuration / 1000, includeHours));
		}
	}

	private String formatTime(int sec, boolean hours) {
		return hours ? String.format(Locale.getDefault(), "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
				: String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
	}

	@Override public void onTransitionAnimationUpdate(float tx, float ty, float s) { listener.setTransitioningViewTransform(tx, ty, s); }
	@Override public void onTransitionAnimationFinished() { listener.endPhotoViewTransition(); }
	@Override public void onSetBackgroundAlpha(float a) { background.setAlpha(Math.round(a * 255f)); uiOverlay.setAlpha(Math.max(0f, a * 2f - 1f)); }
	@Override public void onStartSwipeToDismiss() { 
		listener.setPhotoViewVisibility(pager.getCurrentItem(), false); 
		windowView.removeCallbacks(uiAutoHider);
	}
	@Override public void onSwipeToDismissCanceled() { listener.setPhotoViewVisibility(pager.getCurrentItem(), true); }
	@Override public void onSingleTap() { toggleUI(); }

	@Override
	public void onDismissed() {
		for (ExoPlayer p : players) p.release();
		players.clear();
		if(!players.isEmpty()) activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		wm.removeView(windowView);
		listener.photoViewerDismissed();
	}

	private void toggleUI() {
		uiVisible = !uiVisible;
		uiOverlay.animate().alpha(uiVisible ? 1f : 0f).setDuration(250).withEndAction(() -> { if(!uiVisible) uiOverlay.setVisibility(View.GONE); }).start();
		if (uiVisible) {
			uiOverlay.setVisibility(View.VISIBLE);
			if(attachments.get(currentIndex).type == Attachment.Type.VIDEO) hideUiDelayed();
		}
	}

	private void hideUiDelayed() { windowView.removeCallbacks(uiAutoHider); windowView.postDelayed(uiAutoHider, 3000); }

	private void onPageChanged(int index) {
		currentIndex = index;
		Attachment att = attachments.get(index);
		videoControls.setVisibility(att.type == Attachment.Type.VIDEO ? View.VISIBLE : View.GONE);
		if (att.type == Attachment.Type.VIDEO) {
			videoSeekBar.setSecondaryProgress(0);
			videoDuration = (int) (att.getDuration() * 1000);
			videoLastTimeUpdatePosition = -1;
			updateVideoTimeText(0);
		}
	}

	private void incKeepScreenOn(){
		if(screenOnRefCount==0){
			WindowManager.LayoutParams wlp=(WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags|=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			activity.getSystemService(AudioManager.class).requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
		}
		screenOnRefCount++;
	}

	private void decKeepScreenOn(){
		screenOnRefCount--;
		if(screenOnRefCount==0){
			WindowManager.LayoutParams wlp=(WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags&=~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		}
	}

	public void onPause(){ pauseVideo(); }

	private void shareCurrentFile(){
		Attachment att=attachments.get(pager.getCurrentItem());
		if(att.type!=Attachment.Type.IMAGE){
			shareAfterDownloading(att);
			return;
		}
		UrlImageLoaderRequest req=new UrlImageLoaderRequest(att.url);
		try{
			File file=ImageCache.getInstance(activity).getFile(req);
			if(file==null){ shareAfterDownloading(att); return; }
			MastodonAPIController.runInBackground(()->{
				File imageDir=new File(activity.getCacheDir(), ".");
				File renamedFile=new File(imageDir, Uri.parse(att.url).getLastPathSegment());
				file.renameTo(renamedFile);
				shareFile(renamedFile);
			});
		}catch(IOException x){ Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show(); }
	}

	private void saveCurrentFile(){
		if(Build.VERSION.SDK_INT>=29 || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED){
			doSaveCurrentFile();
		}else{
			listener.onRequestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
		}
	}

	private String mimeTypeForFileName(String fileName){
		int extOffset=fileName.lastIndexOf('.');
		if(extOffset>0){
			return switch(fileName.substring(extOffset+1).toLowerCase()){
				case "jpg", "jpeg" -> "image/jpeg";
				case "png" -> "image/png";
				case "gif" -> "image/gif";
				case "webp" -> "image/webp";
				case "mp4" -> "video/mp4";
				default -> null;
			};
		}
		return null;
	}

	private OutputStream destinationStreamForFile(Attachment att) throws IOException{
		String fileName=Uri.parse(att.url).getLastPathSegment();
		if(Build.VERSION.SDK_INT>=29){
			ContentValues values=new ContentValues();
			values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
			values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
			String mime=mimeTypeForFileName(fileName);
			if(mime!=null) values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
			ContentResolver cr=activity.getContentResolver();
			Uri itemUri=cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
			return cr.openOutputStream(itemUri);
		}else{
			return new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName));
		}
	}

	private void doSaveCurrentFile(){
		Attachment att=attachments.get(pager.getCurrentItem());
		if(att.type==Attachment.Type.IMAGE){
			UrlImageLoaderRequest req=new UrlImageLoaderRequest(att.url);
			try{
				File file=ImageCache.getInstance(activity).getFile(req);
				if(file==null){ saveViaDownloadManager(att); return; }
				MastodonAPIController.runInBackground(()->{
					try(Source src=Okio.source(file); Sink sink=Okio.sink(destinationStreamForFile(att))){
						BufferedSink buf=Okio.buffer(sink);
						buf.writeAll(src); buf.flush();
						activity.runOnUiThread(()->Toast.makeText(activity, R.string.file_saved, Toast.LENGTH_SHORT).show());
					}catch(IOException x){ activity.runOnUiThread(()->Toast.makeText(activity, R.string.error_saving_file, Toast.LENGTH_SHORT).show()); }
				});
			}catch(IOException x){ Toast.makeText(activity, R.string.error_saving_file, Toast.LENGTH_SHORT).show(); }
		}else{ saveViaDownloadManager(att); }
	}

	private void saveViaDownloadManager(Attachment att){
		Uri uri=Uri.parse(att.url);
		DownloadManager.Request req=new DownloadManager.Request(uri);
		req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.getLastPathSegment());
		activity.getSystemService(DownloadManager.class).enqueue(req);
		Toast.makeText(activity, R.string.downloading, Toast.LENGTH_SHORT).show();
	}

	private void shareAfterDownloading(Attachment att){
		Toast.makeText(activity, R.string.downloading, Toast.LENGTH_SHORT).show();
		MastodonAPIController.runInBackground(()->{
			try {
				Request request = new Request.Builder().url(att.url).build();
				Response response = new OkHttpClient().newCall(request).execute();
				File imageDir = new File(activity.getCacheDir(), ".");
				File file = new File(imageDir, Uri.parse(att.url).getLastPathSegment());
				try(OutputStream out = new FileOutputStream(file)){ out.write(response.body().bytes()); }
				shareFile(file);
			} catch(IOException e){ Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show(); }
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

	private void onAudioFocusChanged(int c) { if (c <= 0) pauseVideo(); }

	private void showInfoSheet(){
		pauseVideo();
		PhotoViewerInfoSheet sheet=new PhotoViewerInfoSheet(new ContextThemeWrapper(activity, R.style.Theme_Mastodon_Dark), attachments.get(currentIndex), toolbar.getHeight(), new PhotoViewerInfoSheet.Listener(){
			@Override
			public void onBeforeDismiss(int duration){
				AnimatorSet set=new AnimatorSet();
				set.playTogether(ObjectAnimator.ofFloat(pager, View.TRANSLATION_Y, 0), ObjectAnimator.ofFloat(toolbarWrap, View.ALPHA, 1f));
				set.setDuration(duration).start();
			}
			@Override public void onDismissEntireViewer(){ onStartSwipeToDismissTransition(0); }
			@Override public void onButtonClick(int id){
				if(id==R.id.btn_boost) AccountSessionManager.get(accountID).getStatusInteractionController().setReblogged(status, !status.reblogged, null, r->{});
				else if(id==R.id.btn_favorite) AccountSessionManager.get(accountID).getStatusInteractionController().setFavorited(status, !status.favourited, r->{});
			}
		});
		sheet.setStatus(status); sheet.show();
		ObjectAnimator.ofFloat(pager, View.TRANSLATION_Y, -pager.getHeight()*0.2f).setDuration(300).start();
		ObjectAnimator.ofFloat(toolbarWrap, View.ALPHA, 0f).setDuration(300).start();
	}

	private void onStartSwipeToDismissTransition(float v) {
		pauseVideo();
		onDismissed();
	}

	private class PhotoViewAdapter extends RecyclerView.Adapter<BaseHolder> {
		@Override public int getItemCount() { return attachments.size(); }
		@Override public int getItemViewType(int p) { return (attachments.get(p).type == Attachment.Type.IMAGE) ? 0 : 1; }
		@NonNull @Override public BaseHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) { return vt == 0 ? new PhotoViewHolder() : new GifVViewHolder(); }
		@Override public void onBindViewHolder(@NonNull BaseHolder h, int p) { h.bind(attachments.get(p)); }
		@Override public void onViewDetachedFromWindow(@NonNull BaseHolder h) { if (h instanceof GifVViewHolder g) g.reset(); }
		@Override public void onViewAttachedToWindow(@NonNull BaseHolder h) { if (h instanceof GifVViewHolder g) g.prepareAndStartPlayer(); }
	}

	private abstract class BaseHolder extends BindableViewHolder<Attachment> {
		public ZoomPanView zoomPanView;
		public BaseHolder() { super(new ZoomPanView(activity)); zoomPanView = (ZoomPanView) itemView; zoomPanView.setListener(PhotoViewer.this); }
		@Override public void onBind(Attachment item) { zoomPanView.setScrollDirections(getAbsoluteAdapterPosition() > 0, getAbsoluteAdapterPosition() < attachments.size() - 1); }
	}

	private class PhotoViewHolder extends BaseHolder implements ViewImageLoader.Target {
		public ImageView imageView;
		public PhotoViewHolder() { imageView = new ImageView(activity); zoomPanView.addView(imageView, new FrameLayout.LayoutParams(-2, -2, 17)); }
		@Override public void onBind(Attachment item) { super.onBind(item); ViewImageLoader.load(this, null, new UrlImageLoaderRequest(item.url), false); }
		@Override public void setImageDrawable(Drawable d) { imageView.setImageDrawable(d); }
		@Override public View getView() { return imageView; }
	}

	private class GifVViewHolder extends BaseHolder implements Player.Listener, TextureView.SurfaceTextureListener {
		public TextureView textureView;
		public FrameLayout wrap;
		public ExoPlayer player;
		public boolean playerReady;
		private ProgressBar progressBar;
		private Surface mSurface;

		public GifVViewHolder() {
			wrap = new FrameLayout(activity);
			textureView = new TextureView(activity);
			wrap.addView(textureView);
			zoomPanView.addView(wrap, new FrameLayout.LayoutParams(-2, -2, 17));
			progressBar = new ProgressBar(activity);
			zoomPanView.addView(progressBar, new FrameLayout.LayoutParams(-2, -2, 17));
			textureView.setSurfaceTextureListener(this);
		}

		public void prepareAndStartPlayer() {
			if (player != null) return;
			OkHttpDataSource.Factory df = new OkHttpDataSource.Factory(new OkHttpClient());
			player = new ExoPlayer.Builder(activity).setMediaSourceFactory(new DefaultMediaSourceFactory(df)).build();
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
			if (player != null) { player.stop(); player.release(); players.remove(player); player = null; }
		}

		@Override
		public void onPlaybackStateChanged(int state) {
			if (state == Player.STATE_READY) {
				playerReady = true; progressBar.setVisibility(View.GONE); wrap.setBackground(null);
				if (item.type == Attachment.Type.VIDEO && getAbsoluteAdapterPosition() == currentIndex) {
					incKeepScreenOn();
					startUpdatingVideoPosition(player);
				}
			} else if (state == Player.STATE_BUFFERING) progressBar.setVisibility(View.VISIBLE);
		}

		@Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { mSurface = new Surface(s); if (player != null) player.setVideoSurface(mSurface); }
		@Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { if (mSurface != null) mSurface.release(); mSurface = null; return true; }
		@Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
		@Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
		@Override public void onPlayerError(@NonNull PlaybackException e) { Log.e(TAG, "Player error", e); }
	}

	private GifVViewHolder findCurrentVideoPlayerHolder() {
		RecyclerView rv = (RecyclerView) pager.getChildAt(0);
		RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pager.getCurrentItem());
		return (vh instanceof GifVViewHolder gvh) ? gvh : null;
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
