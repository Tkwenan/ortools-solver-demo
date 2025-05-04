package com.example.timetable_solver_demo.enums;

import lombok.Getter;

@Getter
public enum TimeslotActivity {
  CLASS(0),
  SHORTBREAK(1),
  LONGBREAK(2),
  LUNCHBREAK(3),
  PREPS(4),
  GAMES(5);

  private final int timeslotActivity;

  TimeslotActivity(int t){
      timeslotActivity = t;
  }
}
