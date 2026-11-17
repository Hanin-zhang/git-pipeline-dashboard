package com.githublook.run.dto;

import java.util.List;

/** Options for the filter bar, so the UI never has to guess what exists. */
public record RunFiltersDto(List<String> repos, List<String> branches, List<FilterOption> statuses) {

    public record FilterOption(String value, String label) {
    }
}
