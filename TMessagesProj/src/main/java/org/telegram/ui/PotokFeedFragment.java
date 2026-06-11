package org.telegram.ui;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.ui.ActionBar.BaseFragment;
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {
    @Override
    public View createView(Context context) {
        fragmentView = new FrameLayout(context);
        return fragmentView;
    }
}
