potokDrawerView.setOnDrawerItemClickListener(id -> {
    potokDrawerLayout.closeDrawers();
    if (id == org.telegram.ui.PotokDrawerView.ID_CONTACTS) {
        actionBarLayout.presentFragment(new ContactsActivity(null));
    } else if (id == org.telegram.ui.PotokDrawerView.ID_CALLS) {
        Bundle args = new Bundle();
        args.putInt("type", 0);
        actionBarLayout.presentFragment(new CallLogActivity());
} else if (id == org.telegram.ui.PotokDrawerView.ID_SAVED) {
    Bundle args = new Bundle();
    args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
    potokDrawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
        @Override
        public void onDrawerClosed(View drawerView) {
            potokDrawerLayout.removeDrawerListener(this);
            BaseFragment lastFragment = actionBarLayout.getLastFragment();
            if (lastFragment instanceof MainTabsActivity) {
                ((MainTabsActivity) lastFragment).getDialogsActivity().presentFragment(new ChatActivity(args));
            }
        }
    });
    } else if (id == org.telegram.ui.PotokDrawerView.ID_SETTINGS) {
        actionBarLayout.presentFragment(new SettingsActivity());
    } else if (id == org.telegram.ui.PotokDrawerView.ID_NEW_GROUP) {
        Bundle groupArgs = new Bundle();
        actionBarLayout.presentFragment(new GroupCreateActivity(groupArgs));
    } else if (id == org.telegram.ui.PotokDrawerView.ID_NEW_CHANNEL) {
        Bundle args = new Bundle();
        args.putInt("step", 0);
        actionBarLayout.presentFragment(new ChannelCreateActivity(args));
    
    } else if (id == org.telegram.ui.PotokDrawerView.ID_FOLDERS) {
    actionBarLayout.presentFragment(new FiltersSetupActivity());
    } else if (id == org.telegram.ui.PotokDrawerView.ID_MY_PROFILE) {
        Bundle args = new Bundle();
        args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
        args.putBoolean("my_profile", true);
        actionBarLayout.presentFragment(new ProfileActivity(args, null));
    } else if (id == org.telegram.ui.PotokDrawerView.ID_WALLET) {
        openWalletFromDrawer();
    } else if (id == org.telegram.ui.PotokDrawerView.ID_THEME_TOGGLE) {
        android.content.SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Activity.MODE_PRIVATE);
        String dayThemeName = prefs.getString("lastDayTheme", "Blue");
        if (Theme.getTheme(dayThemeName) == null || Theme.getTheme(dayThemeName).isDark()) {
            dayThemeName = "Blue";
        }
        String nightThemeName = prefs.getString("lastDarkTheme", "Dark Blue");
        if (Theme.getTheme(nightThemeName) == null || !Theme.getTheme(nightThemeName).isDark()) {
            nightThemeName = "Dark Blue";
        }
        Theme.ThemeInfo themeInfo = Theme.getActiveTheme();
        if (dayThemeName.equals(nightThemeName)) {
            if (themeInfo.isDark() || dayThemeName.equals("Dark Blue") || dayThemeName.equals("Night")) {
                dayThemeName = "Blue";
            } else {
                nightThemeName = "Dark Blue";
            }
        }
        boolean toDark;
        if (toDark = dayThemeName.equals(themeInfo.getKey())) {
            themeInfo = Theme.getTheme(nightThemeName);
        } else {
            themeInfo = Theme.getTheme(dayThemeName);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, themeInfo, false, null, -1, toDark, null);
        BaseFragment currentFragment = actionBarLayout.getLastFragment();
        if (currentFragment != null) {
            Theme.turnOffAutoNight(currentFragment);
        }
    } else if (id == org.telegram.ui.PotokDrawerView.ID_SWITCH_ACCOUNT) {
        BaseFragment lastFragment = actionBarLayout.getLastFragment();
        if (lastFragment instanceof MainTabsActivity) {
            View anchor = lastFragment.getFragmentView() != null ? lastFragment.getFragmentView() : drawerLayoutContainer;
            ((MainTabsActivity) lastFragment).openAccountSelector(anchor);
        }
    }
});
