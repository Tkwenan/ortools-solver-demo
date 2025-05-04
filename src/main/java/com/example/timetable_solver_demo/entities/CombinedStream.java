package com.example.timetable_solver_demo.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.JoinTable;
import jakarta.persistence.JoinColumn;
import java.util.Set;
import java.util.HashSet;

@Entity
@Getter
@Setter
@Table(name = "combined_stream")
public class CombinedStream {
  @GeneratedValue
  @Id
  @Column(name = "combined_stream_id")
  private Long id;

  @ManyToMany
  @JoinTable(
      name = "combined_stream_streams",
      joinColumns = @JoinColumn(name = "combined_stream_id"),
      inverseJoinColumns = @JoinColumn(name = "id") //stream id
  )
  private Set<Stream> constituentStreams = new HashSet<>();

  @JoinColumn(name = "subject", referencedColumnName = "subject_id")
  @ManyToOne
  private Subject subject;
}
