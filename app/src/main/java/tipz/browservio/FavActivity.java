package tipz.browservio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Objects;

import tipz.browservio.sharedprefs.AllPrefs;
import tipz.browservio.sharedprefs.utils.BrowservioSaverUtils;
import tipz.browservio.utils.BrowservioBasicUtil;

public class FavActivity extends AppCompatActivity {
	
	private final ArrayList<String> bookmark_list = new ArrayList<>();
	
	private ListView listview;
	
	private SharedPreferences browservio_saver;
	private SharedPreferences bookmarks;
	private AlertDialog.Builder del_fav;

	private Boolean popup = false;
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		setContentView(R.layout.fav);
		initialize();
		setTitle(getResources().getString(R.string.fav));
	}

	/**
	 * Initialize function
	 */
	private void initialize() {

		Toolbar _toolbar = findViewById(R.id._toolbar);
		setSupportActionBar(_toolbar);
		Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setHomeButtonEnabled(true);
		_toolbar.setNavigationOnClickListener(_v -> onBackPressed());
		FloatingActionButton _fab = findViewById(R.id._fab);
		
		listview = findViewById(R.id.listview);
		browservio_saver = getSharedPreferences(AllPrefs.browservio_saver, Activity.MODE_PRIVATE);
		bookmarks = getSharedPreferences(AllPrefs.bookmarks, Activity.MODE_PRIVATE);
		del_fav = new AlertDialog.Builder(this);
		
		listview.setOnItemClickListener((_param1, _param2, _param3, _param4) -> {
			if (!popup) {
				BrowservioSaverUtils.setPref(browservio_saver, AllPrefs.needLoad, "1");
				BrowservioSaverUtils.setPref(browservio_saver, AllPrefs.needLoadUrl, BrowservioSaverUtils.getPref(bookmarks, AllPrefs.bookmark.concat(String.valueOf(_param3))));
				finish();
			} else {
				popup = false;
			}
		});
		
		listview.setOnItemLongClickListener((_param1, _param2, _param3, _param4) -> {
			popup = true;
			PopupMenu popup1 = new PopupMenu(FavActivity.this, _param2);
			Menu menu1 = popup1.getMenu();
			menu1.add(getResources().getString(R.string.del_fav));
			menu1.add(getResources().getString(android.R.string.copyUrl));
			popup1.setOnMenuItemClickListener(item -> {
				if (item.getTitle().toString().equals(getResources().getString(R.string.del_fav))) {
					final int _position = _param3;
					del_fav.setTitle(getResources().getString(R.string.del_fav_title));
					del_fav.setMessage(getResources().getString(R.string.del_fav_title));
					del_fav.setPositiveButton(android.R.string.ok, (_dialog, _which) -> {
						bookmark_list.remove(_position);
						BrowservioSaverUtils.setPref(bookmarks, AllPrefs.bookmark.concat(String.valueOf((long)(_position))).concat(AllPrefs.bookmarked_count_show), "0");
						((BaseAdapter)listview.getAdapter()).notifyDataSetChanged();
						BrowservioBasicUtil.showMessage(getApplicationContext(), getResources().getString(R.string.del_success));
						isEmptyCheck(bookmark_list, bookmarks);
					});
					del_fav.setNegativeButton(android.R.string.cancel, null);
					del_fav.create().show();
					return true;
				} else if (item.getTitle().toString().equals(getResources().getString(android.R.string.copyUrl))) {
					((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("clipboard", BrowservioSaverUtils.getPref(bookmarks, AllPrefs.bookmark.concat(String.valueOf(_param3)))));
					BrowservioBasicUtil.showMessage(getApplicationContext(), getResources().getString(R.string.copied_clipboard));
					return true;
				}
				return false;
			});
			popup1.show();
			return false;
		});
		
		_fab.setOnClickListener(_view -> {
			del_fav.setTitle(getResources().getString(R.string.del_fav2_title));
			del_fav.setMessage(getResources().getString(R.string.del_fav2_message));
			del_fav.setPositiveButton(android.R.string.ok, (_dialog, _which) -> {
				bookmarks.edit().clear().apply();
				BrowservioBasicUtil.showMessage(getApplicationContext(), getResources().getString(R.string.wiped_success));
				finish();
			});
			del_fav.setNegativeButton(android.R.string.cancel, null);
			del_fav.create().show();
		});
	}
	
	@Override
	public void onStart() {
		super.onStart();
		int populate_count = 0;
		boolean loopComplete = false;
		final ProgressDialog PopulationProg = new ProgressDialog(FavActivity.this);
		PopulationProg.setMax(100);
		PopulationProg.setMessage(getResources().getString(R.string.populating_dialog_message));
		PopulationProg.setIndeterminate(true);
		PopulationProg.setCancelable(false);
		PopulationProg.show();
		while (!loopComplete) {
			String shouldShow = BrowservioSaverUtils.getPref(bookmarks, AllPrefs.bookmarked.concat(Integer.toString(populate_count)).concat(AllPrefs.bookmarked_count_show));
			if (!shouldShow.equals("0")) {
				if (shouldShow.equals("")) {
					loopComplete = true;
					isEmptyCheck(bookmark_list, bookmarks);
					listview.setAdapter(new ArrayAdapter<>(getBaseContext(), R.layout.simple_list_item_1_daynight, bookmark_list));
					PopulationProg.dismiss();
				} else {
					String bookmarkTitle = AllPrefs.bookmarked.concat(Integer.toString(populate_count)).concat(AllPrefs.bookmarked_count_title);
						bookmark_list.add(BrowservioSaverUtils.getPref(bookmarks, bookmarkTitle).equals("") ?
								BrowservioSaverUtils.getPref(bookmarks, AllPrefs.bookmarked.concat(Integer.toString(populate_count))) :
								BrowservioSaverUtils.getPref(bookmarks, bookmarkTitle));
				}
			}
			populate_count++;
		}
	}

	private void isEmptyCheck(ArrayList<String> list, SharedPreferences out) {
		// Placed here for old data migration
		if (list.isEmpty()) {
			out.edit().clear().apply();
			BrowservioBasicUtil.showMessage(getApplicationContext(), getResources().getString(R.string.fav_list_empty));
			finish();
		}
	}
}
