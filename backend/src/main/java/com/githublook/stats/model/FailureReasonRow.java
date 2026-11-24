package com.githublook.stats.model;

import lombok.Getter;
import lombok.Setter;

/** One row of the failure-reason aggregation. {@code failureClass} is null for unclassified runs. */
@Getter
@Setter
public class FailureReasonRow {
    private String failureClass;
    private long total;
}
