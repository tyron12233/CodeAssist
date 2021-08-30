package com.tyron.code;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.code.ui.main.MainFragment;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.fragment_container, new MainFragment(), "main_fragment") //FileManagerFragment.newInstance(new File("/sdcard")))
                .commit();
    }
	
	private void test() {
		main();
	}
	public void main() {
		
	}
}
