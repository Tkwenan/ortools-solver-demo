package com.example.timetable_solver_demo.entities;

import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.DayOfWeek;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;

@Table(name = "timeoff_teacher", 
      uniqueConstraints = {@UniqueConstraint(columnNames = {"teacher", "timeslot", "day_of_week"})})
@Entity
@Getter
@Setter
public class TeacherTimeOff {
  @Id
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "teacher", referencedColumnName = "id", nullable = false)
  private Teacher teacher;
  
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "timeslot", referencedColumnName = "id", nullable = false)
  private ScheduleTimeslot timeslot;

  @Enumerated(EnumType.STRING)
  @Column(name = "day_of_week", nullable = false)
  private DayOfWeek dayOfWeek;
}
