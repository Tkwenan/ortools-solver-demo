package com.example.timetable_solver_demo.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Setter
@Getter
@Table(name = "teacher")
public class Teacher {
  @Id
  private Long id;
}
