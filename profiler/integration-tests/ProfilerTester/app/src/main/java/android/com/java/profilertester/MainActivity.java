package android.com.java.profilertester;

import android.com.java.profilertester.leaks.GlobalLeakingObject;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

public class MainActivity extends AppCompatActivity {

    private MainActivityFragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mFragment = (MainActivityFragment) getSupportFragmentManager().findFragmentById(R.id.fragment);

        FloatingActionButton runButton = findViewById(R.id.run_button);
        runButton.setOnClickListener(
                view -> {
                    Snackbar.make(view, "Running", Snackbar.LENGTH_LONG)
                            .setAction("Action", null)
                            .show();
                    mFragment.testScenario();
                });

        FloatingActionButton previousButton = findViewById(R.id.previous_button);
        previousButton.setOnClickListener(
                view -> {
                    if (!mFragment.scenarioMoveBack()) {
                        Snackbar.make(view, "Already on first scenario", Snackbar.LENGTH_LONG)
                                .setAction("Action", null)
                                .show();
                    }
                });

        FloatingActionButton nextButton = findViewById(R.id.next_button);
        nextButton.setOnClickListener(
                view -> {
                    if (!mFragment.scenarioMoveForward()) {
                        Snackbar.make(view, "Already on last scenario", Snackbar.LENGTH_LONG)
                                .setAction("Action", null)
                                .show();
                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onDestroy() {
        GlobalLeakingObject.clearAll();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_settings) {
            // User chose the "Settings" item, show the app settings UI...
            return true;
        } else if (itemId == R.id.action_perf_mode) {
            mFragment.togglePerfMode();
            return true;
        } else {
            // If we got here, the user's action was not recognized.
            // Invoke the superclass to handle it.
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mFragment.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
