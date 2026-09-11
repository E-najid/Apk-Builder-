package {{PACKAGE_NAME}};

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * A minimal starting screen — edit this and make it yours.
 * Everything here is plain Android, no extra dependencies.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView welcome = new TextView(this);
        welcome.setText("Hello from {{APP_NAME}}!");
        welcome.setTextColor(Color.DKGRAY);
        welcome.setTextSize(24f);
        welcome.setGravity(Gravity.CENTER);
        setContentView(welcome);
    }
}
