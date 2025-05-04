package com.example.timetable_solver_demo.enums;

public enum TimetableStatus {
  GENERATING(1),
  NEEDS_REVIEW(2),
  ACTIVE(3),
  SCHEDULED(4),
  ARCHIVED(5);

  private int value;

  TimetableStatus(int value) {
    this.value = value;
}

  public int getValue() {
      return value;
  }

  public void setValue(int value) {
      this.value = value;
  }
}
