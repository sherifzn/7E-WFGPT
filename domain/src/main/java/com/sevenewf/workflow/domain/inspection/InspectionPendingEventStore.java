package com.sevenewf.workflow.domain.inspection;

import java.util.List;

public interface InspectionPendingEventStore {
  void appendPendingResumeEvent(PendingResumeEvent event);

  List<PendingResumeEvent> pendingResumeEvents();

  void markResumeEventHandled(PendingResumeEvent event);
}
