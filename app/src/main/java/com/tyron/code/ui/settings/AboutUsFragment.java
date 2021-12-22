package com.tyron.code.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.tyron.code.BuildConfig;
import com.tyron.code.R;

import mehdi.sakout.aboutpage.AboutPage;
import mehdi.sakout.aboutpage.Element;

public class AboutUsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Element versionElement = new Element();
        versionElement.setTitle(getString(R.string.app_version, BuildConfig.VERSION_NAME));

        return new AboutPage(requireContext())
                .setDescription(getString(R.string.app_description))
                .setImage(R.mipmap.ic_launcher)
                .addItem(versionElement)
                .addGroup(getString(R.string.contact_group_title))
                .addItem(createGithubElement("ThatSketchub", getString(R.string.sketchub_team)))
                .addItem(createGithubElement("tyron12233/CodeAssist", getString(R.string.app_source_title)))
                .addEmail("contact.tyronscott@gmail.com")
                .create();
    }

    public Element createGithubElement(String id, String title) {
        Element gitHubElement = new Element();
        gitHubElement.setTitle(title);
        gitHubElement.setIconDrawable(R.drawable.about_icon_github);
        gitHubElement.setIconTint(R.color.colorControlNormal);
        gitHubElement.setValue(id);

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setData(Uri.parse(String.format("https://github.com/%s", id)));

        gitHubElement.setIntent(intent);
        return gitHubElement;
    }
}
