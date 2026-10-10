package org.z9x.home.ui;

import android.view.KeyEvent;
import android.view.View;

import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;

/** One tab of Lumen Home (Home, Apps, Projector). Focus is managed by hand, see HomeActivity. */
public interface Page {
    interface Host extends RowView.Host, HeroView.Host {
        void onCardClick(Card k, CardView v);

        void onCardMenu(Card k, RowView row);

        /** Browse mode (focus below the first row): the top bar fades out, all but its clock. */
        void onBrowseMode(boolean browse);

        /** Art for the ambient backdrop (null = brand gradient). */
        void onFocusArt(String uri, String pkg);

        void onCustomize();

        /** Home switched between D_Home (hero over the stage art) and D_Calm (big clock over the sky). */
        void onHomeMode(boolean hero);

        /** OK on the calm Home's weather line: the forecast. */
        void onWeatherAction();

        /** Lowest y (real px) the hero's art card may reach: the first row moved up for tall cards. */
        void onHeroBottom(int px);
    }

    View view();

    void bind(HomeModel m);

    /** Focus enters the page from the top bar. */
    void focusIn();

    void focusOut();

    /** DPAD keys and OK. Returns false for UP at the top edge (focus goes to the top bar). */
    boolean onKey(int keyCode, KeyEvent e);

    /** Long press OK / MENU on the focused item. */
    void onMenu();

    /** BACK inside the page: true if consumed (e.g. scroll to top, end move mode). */
    boolean onBack();

    void scrollToTop(boolean animate);

    void setShown(boolean shown);

    /** Drop or restore art for memory pressure / visibility. */
    void trim();

    void reload();
}
