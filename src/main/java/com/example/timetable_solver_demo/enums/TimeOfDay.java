package com.example.timetable_solver_demo.enums;

public enum TimeOfDay {
  MORNING(0),
  AFTERNOON(1),
  EVENING(2),
  NIGHT(3);

  private final int timeOfDay;

  TimeOfDay(int t){
     this.timeOfDay = t;
  }
}
