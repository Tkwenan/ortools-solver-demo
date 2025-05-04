package com.example.timetable_solver_demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.timetable_solver_demo.entities.CombinedStream;
import com.example.timetable_solver_demo.entities.Lesson;
import com.example.timetable_solver_demo.entities.Schedule;
import com.example.timetable_solver_demo.entities.SchoolTimetable;
import com.example.timetable_solver_demo.entities.TeacherTimeOff;
import com.example.timetable_solver_demo.repositories.LessonRepository;
import com.example.timetable_solver_demo.repositories.ScheduleRepository;
import com.example.timetable_solver_demo.repositories.SchoolTimetableRepository;

import java.util.ArrayList;
import java.util.List;

@Component
public class MinimalSolverTest implements CommandLineRunner {
    private final LessonRepository lessonRepo;
    private final ScheduleRepository scheduleRepo;
    private final SchoolTimetableRepository timetableRepository;
    private final DataExportUtility dataExportUtility;

    public MinimalSolverTest(LessonRepository lessonRepo, ScheduleRepository scheduleRepo, SchoolTimetableRepository timetableRepository, DataExportUtility dataExportUtility) {
        this.lessonRepo = lessonRepo;
        this.scheduleRepo = scheduleRepo;
        this.timetableRepository = timetableRepository;
        this.dataExportUtility = dataExportUtility;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Long scheduleId = 33L;
        Long timetableId = 1502L;
        
        // Get the schedule and lessons first
        Schedule schedule = scheduleRepo.findById(scheduleId)
            .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        SchoolTimetable timetable = timetableRepository.findById(timetableId)
            .orElseThrow(() -> new IllegalArgumentException("Timetable not found with ID: " + timetableId));
        List<Lesson> lessons = lessonRepo.fetchAllLessonsByTimetable(timetable);
        List<CombinedStream> combinedStreams = new ArrayList<>();
        List<TeacherTimeOff> teacherTimeOffs = new ArrayList<>();
        
        // Export the data for inspection
        dataExportUtility.exportDataForMRE(scheduleId, timetableId);
        
        // Create and run the minimal solver
        MinimalTimetableSolver solver = new MinimalTimetableSolver(lessons, schedule, combinedStreams, teacherTimeOffs,  lessonRepo);
        solver.solve();
    }
} 