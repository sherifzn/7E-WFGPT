package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public interface BusinessCalendar {
  Instant addBusinessDays(Instant start, BusinessDays businessDays);

  default Instant calculateFrom(Instant start, BusinessDays businessDays) {
    Validation.requirePresent(start, "start");
    Validation.requirePresent(businessDays, "businessDays");
    return Validation.requirePresent(
        addBusinessDays(start, businessDays), "calculatedBusinessTime");
  }
}
