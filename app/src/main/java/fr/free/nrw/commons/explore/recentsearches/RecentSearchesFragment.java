package fr.free.nrw.commons.explore.recentsearches;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import butterknife.BindView;
import butterknife.ButterKnife;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.di.CommonsDaggerSupportFragment;
import fr.free.nrw.commons.explore.SearchActivity;
import java.util.List;
import javax.inject.Inject;


/**
 * Displays the recent searches screen.
 */
public class RecentSearchesFragment extends CommonsDaggerSupportFragment {

    @Inject
    RecentSearchesDao recentSearchesDao;
    @BindView(R.id.recent_searches_list)
    ListView recentSearchesList;
    List<String> recentSearches;
    ArrayAdapter adapter;
    @BindView(R.id.recent_searches_delete_button)
    ImageView recent_searches_delete_button;
    @BindView(R.id.recent_searches_text_view)
    TextView recent_searches_text_view;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
        Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_search_history, container, false);
        ButterKnife.bind(this, rootView);
        recentSearches = recentSearchesDao.recentSearches(10);

        if (recentSearches.isEmpty()) {
            recent_searches_delete_button.setVisibility(View.GONE);
            recent_searches_text_view.setText(R.string.no_recent_searches);
        }

        recent_searches_delete_button.setOnClickListener(v -> {
            showDeleteRecentAlertDialog(requireContext());
        });

        adapter = new ArrayAdapter<>(requireContext(), R.layout.item_recent_searches,
            recentSearches);
        recentSearchesList.setAdapter(adapter);
        recentSearchesList.setOnItemClickListener((parent, view, position, id) -> (
            (SearchActivity) getContext()).updateText(recentSearches.get(position)));
        recentSearchesList.setOnItemLongClickListener((parent, view, position, id) -> {
            showDeleteAlertDialog(requireContext(), position);
            return true;
        });
        updateRecentSearches();
        return rootView;
    }

    private void showDeleteRecentAlertDialog(@NonNull final Context context) {
        new AlertDialog.Builder(context)
            .setMessage(getString(R.string.delete_recent_searches_dialog))
            .setPositiveButton(android.R.string.yes,
                (dialog, which) -> setDeleteRecentPositiveButton(context, dialog))
            .setNegativeButton(android.R.string.no, null)
            .create()
            .show();
    }

    private void setDeleteRecentPositiveButton(@NonNull final Context context,
        final DialogInterface dialog) {
        recentSearchesDao.deleteAll();
        recent_searches_delete_button.setVisibility(View.GONE);
        recent_searches_text_view.setText(R.string.no_recent_searches);
        Toast.makeText(getContext(), getString(R.string.search_history_deleted),
            Toast.LENGTH_SHORT).show();
        recentSearches = recentSearchesDao.recentSearches(10);
        adapter = new ArrayAdapter<>(context, R.layout.item_recent_searches,
            recentSearches);
        recentSearchesList.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        dialog.dismiss();
    }

    private void showDeleteAlertDialog(@NonNull final Context context, final int position) {
        new AlertDialog.Builder(context)
            .setMessage(R.string.delete_search_dialog)
            .setPositiveButton(getString(R.string.delete).toUpperCase(),
                ((dialog, which) -> setDeletePositiveButton(context, dialog, position)))
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show();
    }

    private void setDeletePositiveButton(@NonNull final Context context,
        final DialogInterface dialog, final int position) {
        recentSearchesDao.delete(recentSearchesDao.find(recentSearches.get(position)));
        recentSearches = recentSearchesDao.recentSearches(10);
        adapter = new ArrayAdapter<>(context, R.layout.item_recent_searches,
            recentSearches);
        recentSearchesList.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        dialog.dismiss();
    }

    /**
     * This method is called on back press of activity so we are updating the list from database to
     * refresh the recent searches list.
     */
    @Override
    public void onResume() {
        updateRecentSearches();
        super.onResume();
    }

    /**
     * This method is called when search query is null to update Recent Searches
     */
    public void updateRecentSearches() {
        recentSearches = recentSearchesDao.recentSearches(10);
        adapter.notifyDataSetChanged();

        if (!recentSearches.isEmpty()) {
            recent_searches_delete_button.setVisibility(View.VISIBLE);
            recent_searches_text_view.setText(R.string.search_recent_header);
        }
    }
}
