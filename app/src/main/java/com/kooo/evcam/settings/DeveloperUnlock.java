package com.kooo.evcam.settings;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

/**
 * 开发者选项的开关弹框（原来在关于页里点「安全须知」；关于页没了，2.13 起挪到这里）。
 *
 * <p>开：设置界面的标题连点七下，输密码。关：开发者选项分区最后一行。
 * 门槛是密码本身，连点七下只是为了不让人平时误碰到。</p>
 */
public final class DeveloperUnlock {

    private DeveloperUnlock() {
    }

    /** @param after 开 / 关成功之后叫一声，让左栏重建 */
    public static void promptForPassword(Context context, Runnable after) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.dev_unlock_hint);
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dev_unlock_title)
                .setMessage(R.string.dev_unlock_msg)
                .setView(input)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    if (DeveloperMode.unlock(context, input.getText().toString())) {
                        Toast.makeText(context, R.string.dev_unlocked, Toast.LENGTH_LONG).show();
                        if (after != null) {
                            after.run();
                        }
                    } else {
                        Toast.makeText(context, R.string.dev_wrong_password, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 开发者选项打开之后一直开着，关只在这里关（见 {@link DeveloperMode}）。 */
    public static void promptToLock(Context context, Runnable after) {
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dev_lock_title)
                .setMessage(R.string.dev_lock_msg)
                .setPositiveButton(R.string.dev_lock_confirm, (dialog, which) -> {
                    DeveloperMode.lock(context);
                    Toast.makeText(context, R.string.dev_locked, Toast.LENGTH_SHORT).show();
                    if (after != null) {
                        after.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }
}
