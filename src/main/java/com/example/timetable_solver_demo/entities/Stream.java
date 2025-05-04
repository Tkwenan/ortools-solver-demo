package com.example.timetable_solver_demo.entities;

import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
@Setter
@Getter
@Table(name = "stream")
public class Stream {
  @Id
  private Integer id;

  private String streamName;

  @ManyToOne
  @JoinColumn(name = "intake")
  private Intake intake;
}
