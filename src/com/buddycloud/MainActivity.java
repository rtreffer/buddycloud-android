package com.buddycloud;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.view.KeyEvent;
import android.view.Window;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.buddycloud.fragments.ChannelStreamFragment;
import com.buddycloud.fragments.ContentFragment;
import com.buddycloud.fragments.GenericChannelsFragment;
import com.buddycloud.fragments.SearchChannelsFragment;
import com.buddycloud.fragments.SubscribedChannelsFragment;
import com.buddycloud.log.Logger;
import com.buddycloud.model.SyncModel;
import com.buddycloud.notifications.GCMEvent;
import com.buddycloud.notifications.GCMIntentService;
import com.buddycloud.notifications.GCMUtils;
import com.buddycloud.preferences.Preferences;
import com.buddycloud.utils.ActionbarUtil;
import com.buddycloud.utils.Backstack;
import com.jeremyfeinstein.slidingmenu.lib.SlidingMenu;
import com.jeremyfeinstein.slidingmenu.lib.SlidingMenu.OnClosedListener;
import com.jeremyfeinstein.slidingmenu.lib.SlidingMenu.OnOpenedListener;
import com.jeremyfeinstein.slidingmenu.lib.app.SlidingFragmentActivity;

public class MainActivity extends SlidingFragmentActivity {

	private static final String TAG = MainActivity.class.getName();

	private String myJid;
	private Backstack backStack;
	
	private ChannelStreamFragment channelStreamFrag;
	private SubscribedChannelsFragment subscribedFrag;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		
		ActionbarUtil.showActionBar(this, getString(R.string.app_name), true);

		setSlidingActionBarEnabled(false);		
		setBehindContentView(R.layout.menu_frame);
		setContentView(R.layout.content_frame);
		
		this.backStack = new Backstack(this);
		
		if (savedInstanceState != null) {
			channelStreamFrag = (ChannelStreamFragment) getSupportFragmentManager().getFragment(
					savedInstanceState, "mContent");
		}
		
		// Check if the welcome screen visible for login.
		// Otherwise, show the main screen.
		if (shouldLogin()) {
			Intent welcomeActivity = new Intent();
			welcomeActivity.setClass(getApplicationContext(), WelcomeActivity.class);
			startActivityForResult(welcomeActivity, WelcomeActivity.REQUEST_CODE);
		} else { 
			startActivity();
		}
	}
	
	// You need to do the Play Services APK check here too.
	@Override
	protected void onResume() {
	    super.onResume();
	}

	 @Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (channelStreamFrag != null) {
			getSupportFragmentManager().putFragment(outState, "mContent", channelStreamFrag);
		}
	}

	private boolean shouldLogin() {
		return Preferences.getPreference(this, Preferences.MY_CHANNEL_JID) == null || 
				Preferences.getPreference(this, Preferences.PASSWORD) == null || 
				Preferences.getPreference(this, Preferences.API_ADDRESS) == null;
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			onBackPressed();
			return false;
	    }
	    return true;
	}
	
	@Override
	public void onBackPressed() {
		if (getSlidingMenu().isMenuShowing()) {
			finish();
		} else {
			if (!backStack.pop()) {
				getSlidingMenu().showMenu();
			}
		}
	}
	
	private ContentFragment getCurrentFragment() {
		if (getSlidingMenu().isMenuShowing()) {
			return subscribedFrag;
		} 
		return channelStreamFrag;
	}
	
	public ChannelStreamFragment getChannelStreamFrag() {
		return channelStreamFrag;
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == WelcomeActivity.REQUEST_CODE) {
			if (resultCode == WelcomeActivity.RESULT_CODE_OK) {
				startActivity();
			} else {
				finish();
			}
		} else if (requestCode == SearchActivity.REQUEST_CODE) {
			if (data != null) {
				String channelJid = data.getStringExtra(GenericChannelsFragment.CHANNEL);
				String filter = data.getStringExtra(SearchChannelsFragment.FILTER);
				showChannelFragment(channelJid);
				backStack.pushSearch(filter);
			} else {
				backStack.pop();
			}
		} else if (requestCode == GenericChannelActivity.REQUEST_CODE) {
			if (data != null) {
				final String channelJid = data.getStringExtra(GenericChannelsFragment.CHANNEL);
				showChannelFragment(channelJid);
				backStack.pushGeneric(data.getBundleExtra(GenericChannelsFragment.INPUT_ARGS));
			} else {
				backStack.pop();
			}
		} else if (requestCode == PostNewTopicActivity.REQUEST_CODE) {
			if (resultCode == PostNewTopicActivity.NEW_POST_CREATED_RESULT) {
				channelStreamFrag.synchPostStream();
			}
			else if (data != null) {
				final String channelJid = data.getStringExtra(GenericChannelsFragment.CHANNEL);
				showChannelFragment(channelJid);
				backStack.pushGeneric(data.getBundleExtra(GenericChannelsFragment.INPUT_ARGS));
			}
			else {
				backStack.pop();
			}
		}
	}

	@Override
	public void onAttachedToWindow() {
		Uri data = getIntent().getData();
		String scheme = getIntent().getScheme();
		String channelJid = null;
		
		if (data != null && scheme != null && scheme.equals(Preferences.BUDDYCLOUD_SCHEME)) {
			channelJid = data.getSchemeSpecificPart();
		}
		if (channelJid == null) {
			channelJid = getIntent().getStringExtra(GenericChannelsFragment.CHANNEL);
		}
		
		if (channelJid == null) {
			showChannelFragment(myJid, false, false);
			showMenu();
		} else {
			showChannelFragment(channelJid);
		}
		
		String event = getIntent().getStringExtra(GCMIntentService.GCM_NOTIFICATION_EVENT);
		if (event != null) {
			GCMEvent gcmEvent = GCMEvent.valueOf(event);
			process(gcmEvent);
		}
		super.onAttachedToWindow();
	}
	

	private void startActivity() {
		this.myJid = (String) Preferences.getPreference(this, Preferences.MY_CHANNEL_JID);
		
		registerInGCM();
		addMenuFragment();
		customizeMenu();
	}
	
	private void process(GCMEvent event) {
		switch (event) {
		case POST_AFTER_MY_POST:
		case POST_ON_SUBSCRIBED_CHANNEL:
		case POST_ON_MY_CHANNEL:
		case MENTION:
			GCMUtils.clearGCMAuthors(this);
		default:
			break;
		}
	}
	
	private void registerInGCM() {
		try {
			GCMUtils.issueGCMRegistration(this);
		} catch (Exception e) {
			Logger.error(TAG, "Failed to register in GCM.", e);
		}
	}
	
	public Backstack getBackStack() {
		return backStack;
	}

	public ChannelStreamFragment showChannelFragment(String channelJid) {
		return showChannelFragment(channelJid, false, true);
	}
	
	public ChannelStreamFragment showChannelFragment(String channelJid, 
			boolean pushPreviousToBackstack, boolean clearCounters) {
		
		if (!pushPreviousToBackstack && channelStreamFrag != null) {
			String previousChannel = channelStreamFrag.getArguments().getString(
					GenericChannelsFragment.CHANNEL);
			if (previousChannel != null) {
				getBackStack().pushChannel(previousChannel);
			}
		}
		
		this.channelStreamFrag = new ChannelStreamFragment();
		Bundle args = new Bundle();
		args.putString(GenericChannelsFragment.CHANNEL, channelJid);
		channelStreamFrag.setArguments(args);
		
		if (clearCounters) {
			SyncModel.getInstance().visitChannel(this, channelJid);
		}
		
		getSupportFragmentManager()
        	.beginTransaction()
        	.replace(R.id.content_frame, channelStreamFrag)
        	.commitAllowingStateLoss();
		
		if (getSlidingMenu().isMenuShowing()) {
			getSlidingMenu().showContent();
		} else {
			fragmentChanged();
		}
		
		return channelStreamFrag;
	}
	
	private void customizeMenu() {
		SlidingMenu sm = getSlidingMenu();
		sm.setShadowWidthRes(R.dimen.shadow_width);
		sm.setShadowDrawable(R.drawable.shadow);
		sm.setBehindOffsetRes(R.dimen.slidingmenu_offset);
		sm.setFadeDegree(0.15f);
		sm.setTouchModeAbove(SlidingMenu.TOUCHMODE_FULLSCREEN);
		sm.setOnOpenedListener(new OnOpenedListener() {
			@Override
			public void onOpened() {
				fragmentChanged();
			}
		});
		
		sm.setOnClosedListener(new OnClosedListener() {
			@Override
			public void onClosed() {
				fragmentChanged();
			}
		});
	}

	private void addMenuFragment() {
		this.subscribedFrag = new SubscribedChannelsFragment();
		FragmentTransaction t = this.getSupportFragmentManager().beginTransaction();
		t.replace(R.id.menu_frame, subscribedFrag);
		t.commitAllowingStateLoss();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		ContentFragment currentFragment = getCurrentFragment();
		if (currentFragment != null) {
			currentFragment.createOptions(menu);
		}
		return super.onCreateOptionsMenu(menu);
	}
	
	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
	        showMenu();
	        return true;
	    }
		return getCurrentFragment().menuItemSelected(featureId, item);
	}

	private void fragmentChanged() {
		getCurrentFragment().attached(this);
		supportInvalidateOptionsMenu();
	}
}
