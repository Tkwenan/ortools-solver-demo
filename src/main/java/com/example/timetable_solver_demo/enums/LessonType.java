package com.example.timetable_solver_demo.enums;

import lombok.Getter;

@Getter
public enum LessonType {
  SINGLE(0),
  DOUBLE(1);

  private final int type;

  LessonType(final int type){
      this.type = type;
  }
}
