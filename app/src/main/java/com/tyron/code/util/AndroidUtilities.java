package com.tyron.code.util;
import android.util.TypedValue;
import com.tyron.code.ApplicationLoader;
import android.view.ViewGroup;
import android.view.View;

@SuppressWarnings("unused")
public class AndroidUtilities {
    
    public static int dp(float px) {
        return Math.round(ApplicationLoader.applicationContext
				.getResources().getDisplayMetrics().density * px);
    }

	/**
	 * Converts a dp value into px that can be applied on margins, paddings etc
	 * @param dp The dp value that will be converted into px
	 * @return The converted px value from the dp argument given
	 */
	public static int dpToPx(float dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
				dp, ApplicationLoader.applicationContext.getResources().getDisplayMetrics()));
	}
	
	public static int getHeight(ViewGroup viewGroup) {

		int height = 0;
		for (int i = 0; i < viewGroup.getChildCount(); i++) {
			View view = viewGroup.getChildAt(i);
			
			height += view.getMeasuredHeight();
		}
		
		return height;
	}
}
