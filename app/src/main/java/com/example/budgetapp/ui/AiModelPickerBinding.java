package com.example.budgetapp.ui;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.budgetapp.R;
import com.example.budgetapp.ai.AiEndpoint;
import com.example.budgetapp.ai.AiModelCatalog;
import com.example.budgetapp.ai.AiModelDiscovery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Shared view binding for unsaved text/vision/audio endpoints and profile editors. Main-thread only. */
final class AiModelPickerBinding implements AutoCloseable {
    static final class Connection {
        final String url, key;
        Connection(String url, String key) {
            this.url = url == null ? "" : url.trim();
            this.key = key == null ? "" : key.trim();
        }
        boolean same(Connection other) { return other != null && url.equals(other.url) && key.equals(other.key); }
        boolean ready() {
            if (key.isEmpty()) return false;
            try { AiEndpoint.resolve(url, "/models"); return true; }
            catch (IOException ignored) { return false; }
        }
    }

    private final AppCompatActivity activity;
    private final EditText model;
    private final Supplier<Connection> connection;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AiModelDiscovery discovery;
    private final Button choose, refresh;
    private final TextView status;
    private final View progress;
    private final List<EditText> observed = new ArrayList<>();
    private List<String> models = Collections.emptyList();
    private Connection current;
    private boolean loading, closed, openWhenReady;
    private AlertDialog picker;
    private final Runnable automatic = () -> refresh(false);
    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) { connectionChanged(); }
        @Override public void afterTextChanged(Editable text) { }
    };

    AiModelPickerBinding(AppCompatActivity activity, View root, EditText model,
            Supplier<Connection> connection, AiModelCatalog catalog) {
        this.activity = activity;
        this.model = model;
        this.connection = connection;
        discovery = new AiModelDiscovery(catalog::list, task -> handler.post(task));
        choose = root.findViewById(R.id.btn_choose_remote_model);
        refresh = root.findViewById(R.id.btn_refresh_model_list);
        status = root.findViewById(R.id.tv_model_list_status);
        progress = root.findViewById(R.id.progress_model_list);
        choose.setOnClickListener(view -> {
            if (!models.isEmpty()) showPicker();
            else { openWhenReady = true; if (!loading) refresh(true); }
        });
        refresh.setOnClickListener(view -> refresh(true));
    }

    void observe(EditText... fields) {
        for (EditText field : fields) {
            observed.add(field);
            field.addTextChangedListener(watcher);
        }
        connectionChanged();
    }

    private void connectionChanged() {
        if (closed) return;
        Connection input = connection.get();
        // Changes in a shared field need not invalidate a fully independent endpoint.
        if (input.same(current)) return;
        handler.removeCallbacks(automatic);
        discovery.invalidate();
        current = null;
        loading = false;
        openWhenReady = false;
        models = Collections.emptyList();
        if (picker != null) picker.dismiss();
        choose.setText(R.string.ai_models_choose);
        progress.setVisibility(View.GONE);
        refresh.setEnabled(true);
        status.setText(R.string.ai_models_hint);
        if (input.ready()) handler.postDelayed(automatic, 900);
    }

    private void refresh(boolean explicit) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        Connection input = connection.get();
        if (!explicit && input.same(current)) return;
        handler.removeCallbacks(automatic);
        if (!input.ready()) {
            status.setText(R.string.ai_models_config_error);
            openWhenReady = false;
            return;
        }
        current = input;
        models = Collections.emptyList();
        if (picker != null) picker.dismiss();
        choose.setText(R.string.ai_models_choose);
        loading = true;
        refresh.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.ai_models_loading);
        discovery.request(input.url, input.key, result -> {
            if (closed || activity.isFinishing() || activity.isDestroyed() || !input.same(connection.get())) return;
            loading = false;
            refresh.setEnabled(true);
            progress.setVisibility(View.GONE);
            if (result.failure != null) {
                status.setText(errorMessage(result.failure));
                openWhenReady = false;
                return;
            }
            models = result.models;
            choose.setText(activity.getString(R.string.ai_models_choose_count, models.size()));
            status.setText(activity.getString(R.string.ai_models_loaded, models.size()));
            if (openWhenReady) { openWhenReady = false; showPicker(); }
        });
    }

    private void showPicker() {
        if (closed || models.isEmpty() || !connection.get().same(current)) return;
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_remote_model_picker, null);
        EditText search = content.findViewById(R.id.et_model_search);
        ListView list = content.findViewById(R.id.list_remote_models);
        TextView empty = content.findViewById(R.id.tv_model_search_empty);
        List<String> filtered = new ArrayList<>(models);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_single_choice, filtered);
        list.setAdapter(adapter);
        int selectedModel = filtered.indexOf(model.getText().toString().trim());
        if (selectedModel >= 0) list.setItemChecked(selectedModel, true);
        picker = new AlertDialog.Builder(activity).setTitle(R.string.ai_models_choose).setView(content)
                .setNegativeButton(android.R.string.cancel, null).create();
        AlertDialog dialog = picker;
        dialog.setOnDismissListener(ignored -> { if (picker == dialog) picker = null; });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (closed || !connection.get().same(current)) { dialog.dismiss(); return; }
            model.setText(filtered.get(position));
            model.setSelection(model.length());
            dialog.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                for (String name : models) if (name.toLowerCase(Locale.ROOT).contains(query)) filtered.add(name);
                adapter.notifyDataSetChanged();
                list.clearChoices();
                int selected = filtered.indexOf(model.getText().toString().trim());
                if (selected >= 0) list.setItemChecked(selected, true);
                empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        dialog.show();
    }

    private int errorMessage(AiModelCatalog.Failure failure) {
        switch (failure) {
            case CONFIG: return R.string.ai_models_config_error;
            case AUTH: return R.string.ai_models_auth_error;
            case UNSUPPORTED: return R.string.ai_models_unsupported;
            case RATE_LIMIT: return R.string.ai_models_rate_limit;
            case SERVER: return R.string.ai_models_server_error;
            case REDIRECT: return R.string.ai_models_redirect;
            case INVALID_RESPONSE: return R.string.ai_models_invalid_response;
            case EMPTY: return R.string.ai_models_empty;
            case TOO_LARGE: return R.string.ai_models_too_large;
            default: return R.string.ai_models_network_error;
        }
    }

    @Override public void close() {
        closed = true;
        handler.removeCallbacks(automatic);
        discovery.close();
        if (picker != null) picker.dismiss();
        for (EditText field : observed) field.removeTextChangedListener(watcher);
        observed.clear();
        models = Collections.emptyList();
        current = null;
    }
}
