package com.tyron.code.ui.main

import com.tyron.code.databinding.MainFragmentBinding

class ToolbarManager {

    private lateinit var binding: MainFragmentBinding

    fun bind(binding: MainFragmentBinding) {
        this.binding = binding
    }

    interface OnPanelClickListener {

    }
}