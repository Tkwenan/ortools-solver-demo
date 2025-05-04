package com.example.timetable_solver_demo;

import com.example.timetable_solver_demo.entities.CombinedStream;
import com.example.timetable_solver_demo.entities.CombinedSubject;
import com.example.timetable_solver_demo.entities.Lesson;
import com.example.timetable_solver_demo.entities.LessonInfo;
import com.example.timetable_solver_demo.entities.Schedule;
import com.example.timetable_solver_demo.entities.ScheduleTimeslot;
import com.example.timetable_solver_demo.entities.Stream;
import com.example.timetable_solver_demo.entities.Subject;
import com.example.timetable_solver_demo.entities.Teacher;
import com.example.timetable_solver_demo.entities.TeacherTimeOff;
import com.example.timetable_solver_demo.enums.LessonType;
import com.example.timetable_solver_demo.enums.TimeOfDay;
import com.example.timetable_solver_demo.enums.TimeslotActivity;
import com.example.timetable_solver_demo.repositories.LessonRepository;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.google.ortools.util.Domain;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Iterator;

@Slf4j
public class MinimalTimetableSolver {
    private final List<Lesson> lessons;
    private final Schedule schedule;
    private final List<CombinedStream> combinedStreams;
    private final List<TeacherTimeOff> teacherTimeOffs;
    private final LessonRepository lessonRepo;
    private final CpModel model;
    private final CpSolver solver;

    private final Map<String, Runnable> constraintMethods;

    // Schedule dimensions
    private final int D; // Number of days
    private final int T; // Number of timeslots per day

    //timeslots where timeslot activity == CLASS
    private List<Integer> validClassSlots;

    // Maps to store decision variables
    private Map<Lesson, IntVar> lessonSlots = new HashMap<>();  // For all lessons
    private Map<Lesson, IntVar> doubleLessonSecondSlots = new HashMap<>();  // Only for double lessons' second slots

     //Constants
   //subject codes
   private static final int MATH_CODE = 121;
   private static final int ENGLISH_CODE = 101;
   private static final int KISWAHILI_CODE = 102;


   //Penalty variables for soft constraints
    private List<IntVar> pePenaltyVars = new ArrayList<>();
    private List<IntVar> mathSciencePenaltyVars = new ArrayList<>();
    private List<IntVar> englishKiswahiliPenaltyVars = new ArrayList<>();
    private List<IntVar> teacherConsecutiveLessonsAcrossAllStreamsPenaltyVars = new ArrayList<>();
    private List<IntVar> teacherConsecutiveLessonSameStreamPenaltyVars = new ArrayList<>();
    private List<IntVar> scienceDoubleFirstMondayLessonPenaltyVars = new ArrayList<>();
    private List<IntVar> discourageScienceDoubleAfternoonLesson = new ArrayList<>();
  
    // Constants for subject categories
    private static final String MATHEMATICS_CATEGORY = "Mathematics";
    private static final String SCIENCES_CATEGORY = "Sciences";

    public MinimalTimetableSolver(List<Lesson> lessons, Schedule schedule, List<CombinedStream> combinedStreams, List<TeacherTimeOff> timeOffEntities,LessonRepository lessonRepo) {
        Loader.loadNativeLibraries();
        this.model = new CpModel();
        this.solver = new CpSolver();
        
        // Configure solver parameters
        solver.getParameters().setMaxTimeInSeconds(300); // 5 minutes
        solver.getParameters().setNumSearchWorkers(8);
        solver.getParameters().setLogSearchProgress(true);
        
        this.lessons = lessons;
        this.schedule = schedule;
        this.combinedStreams = combinedStreams;
        this.teacherTimeOffs = timeOffEntities;
        this.lessonRepo = lessonRepo;
        this.D = 5; // Monday to Friday
        this.T = schedule.getTimeslotList().size();
        this.lessonSlots = new HashMap<>();
        this.validClassSlots = new ArrayList<>();

        this.constraintMethods = initializeConstraintMethods();

        // Initialize valid class slots
        for (int day = 0; day < D; day++) {
            for (int slot = 0; slot < T; slot++) {
                if (schedule.getTimeslotList().get(slot).getTimeslotActivity() == TimeslotActivity.CLASS) {
                    int globalSlot = day * T + slot;
                    validClassSlots.add(globalSlot);
                    System.out.println(String.format("Day %d, Slot %d -> Global slot %d", day + 1, slot + 1, globalSlot));
                }
            }
        }
        
        // Log initial setup
        System.out.println("\nSolver Initialization:");
        System.out.println("---------------------");
        System.out.println("Number of lessons: " + lessons.size());
        System.out.println("Number of days: " + D);
        System.out.println("Number of timeslots per day: " + T);
        System.out.println("Number of valid class slots: " + validClassSlots.size());
        System.out.println("Solver parameters:");
        System.out.println("- Max time: " + solver.getParameters().getMaxTimeInSeconds() + " seconds");
        System.out.println("- Search workers: " + solver.getParameters().getNumSearchWorkers());
        
        // Log lessons by stream
        Map<Stream, List<Lesson>> lessonsByStream = lessons.stream()
            .collect(Collectors.groupingBy(lesson -> lesson.getLessonInfo().getStream()));
        System.out.println("\nLessons by stream:");
        for (Map.Entry<Stream, List<Lesson>> entry : lessonsByStream.entrySet()) {
            System.out.println("Stream " + entry.getKey().getId() + ": " + entry.getValue().size() + " lessons");
            int singleLessons = (int) entry.getValue().stream()
                .filter(l -> l.getLessonInfo().getLessonType() == LessonType.SINGLE)
                .count();
            int doubleLessons = (int) entry.getValue().stream()
                .filter(l -> l.getLessonInfo().getLessonType() == LessonType.DOUBLE)
                .count();
            System.out.println("  - Single lessons: " + singleLessons);
            System.out.println("  - Double lessons: " + doubleLessons);
        }
    }

    private Map<String, Runnable> initializeConstraintMethods() {
        Map<String, Runnable> methods = new HashMap<>();
        methods.put("teacher_conflict", this::noOverlappingLessonAcrossStreams);
        methods.put("teacher_unavailability", this::enforceTeacherUnavailability);
        methods.put("lesson_distribution", this::lessThanFiveLessonsPerWeekPerStream);
        methods.put("combined_streams_to_be_taught_at_same_time", this::applyCombinedStreamsConstraint);
        methods.put("hard_subjects_taught_at_most_twice_per_day", this::limitCoreSubjectsPerDay);
        methods.put("pe_lessons_before_break", this::implementPELessonsNearBreaks);
        methods.put("math_sciences_constraint", this::implementMathScienceNonAdjacency);
        methods.put("kiswahili_english_constraint", this::implementEnglishKiswahiliNonAdjacency);
        methods.put("more_than_two_consecutive_lessons_for_teacher", this::implementTeacherConsecutiveLessonsAcrossStreams);
        methods.put("consecutive_lessons_in_same_stream", this::implementTeacherConsecutiveLessonsSameStream);
        methods.put("science_lab_lessons_monday_morning", this::discourageScienceDoubleFirstMondayLesson);
        methods.put("science_double_lessons_afternoon", this::discourageScienceDoubleAfternoonLessons);
        return methods;
    }

    private void initializeDoubleLesson(Lesson doubleLesson) {
        List<Integer> validDoubleSlots = new ArrayList<>();
        List<Integer> validSecondDoubleLessonSlots = new ArrayList<>();

        for (int i = 0; i < validClassSlots.size() - 1; i++) {
            if (validClassSlots.get(i + 1) == validClassSlots.get(i) + 1) {
                validDoubleSlots.add(validClassSlots.get(i)); // Store only the starting slot
                validSecondDoubleLessonSlots.add(validClassSlots.get(i+1));
            }
        }

        // Create two consecutive variables for the double lesson
        IntVar firstSlot =  model.newIntVarFromDomain(Domain.fromValues(validDoubleSlots.stream().mapToLong(Integer::longValue).toArray()), "double_" + doubleLesson.getId() + "_first");
        IntVar secondSlot = model.newIntVarFromDomain(Domain.fromValues(validSecondDoubleLessonSlots.stream().mapToLong(Integer::longValue).toArray()), "double_" + doubleLesson.getId() + "_second");
        
        // Ensure second slot follows first slot
        model.addEquality(secondSlot, LinearExpr.sum(new IntVar[] {firstSlot, model.newConstant(1)}));
        
        // Ensure both slots are on the same day
        // First slot's day = floor(firstSlot / T)
        // Second slot's day = floor(secondSlot / T)
        // These must be equal
        IntVar firstDay = model.newIntVar(0, D - 1, "double_" + doubleLesson.getId() + "_day");
        model.addEquality(firstDay, LinearExpr.term(firstSlot, 1L/T));
        model.addEquality(firstDay, LinearExpr.term(secondSlot, 1L/T));
        
        // Store both slots for this lesson
        lessonSlots.put(doubleLesson, firstSlot);
        doubleLessonSecondSlots.put(doubleLesson, secondSlot);
    }

    private void createDecisionVariables() {
      System.out.println("\nCreating Decision Variables:");
      System.out.println("---------------------------");
      System.out.println("Valid class slots: " + validClassSlots);
      System.out.println("Min valid slot: " + validClassSlots.get(0));
      System.out.println("Max valid slot: " + validClassSlots.get(validClassSlots.size() - 1));

      // Create a decision variable for each lesson
      for (Lesson lesson : lessons) {
        if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE) {
          initializeDoubleLesson(lesson);
        } else {
          String varName = "lesson_" + lesson.getId();
              // Create variable with explicit bounds
              IntVar lessonVar = model.newIntVar(
                  validClassSlots.get(0),  // min value
                  validClassSlots.get(validClassSlots.size() - 1),  // max value
                  varName
              );
              
              // Add explicit constraint to ensure only valid slots are used
              List<BoolVar> validSlotVars = new ArrayList<>();
              for (int validSlot : validClassSlots) {
                  BoolVar isInSlot = model.newBoolVar(varName + "_in_slot_" + validSlot);
                  model.addEquality(lessonVar, validSlot).onlyEnforceIf(isInSlot);
                  model.addDifferent(lessonVar, validSlot).onlyEnforceIf(isInSlot.not());
                  validSlotVars.add(isInSlot);
              }
              model.addExactlyOne(validSlotVars.toArray(new Literal[0]));
        
          lessonSlots.put(lesson, lessonVar);
        }
      }

      // Log the lessons and their types
      System.out.println("\nLessons to Schedule:");
      System.out.println("-------------------");
      for (Lesson lesson : lessons) {
        String teacherIds = lesson.getLessonInfo().getTeachers().stream()
            .map(teacher -> teacher.getId().toString())
            .collect(Collectors.joining(", "));

        System.out.println(String.format("Lesson ID: %d | Type: %s | Stream ID: %d | Teacher IDs: [%s]", 
            lesson.getId(),
            lesson.getLessonInfo().getLessonType(),
            lesson.getLessonInfo().getStream().getId(),
            teacherIds));
      }

      // Debug information about decision variables
      System.out.println("\nDecision Variables:");
      System.out.println("------------------");
      System.out.println("Total lessons: " + lessons.size());
      System.out.println("Single lessons: " + lessonSlots.size());
      System.out.println("Double lessons: " + doubleLessonSecondSlots.size());
      System.out.println("Valid slot range: " + validClassSlots.get(0) + " to " + validClassSlots.get(validClassSlots.size() - 1));
      System.out.println("Valid class slots: " + validClassSlots.size());
    }

    private void enforceLessonNonOverlapConstraints() {
      System.out.println("\nEnforcing lesson non-overlap constraints");
      System.out.println("--------------------------------------");

      // For each stream, ensure no two lessons overlap
      Map<Stream, List<Lesson>> lessonsByStream = new HashMap<>();

      // Group lessons by stream
      for (Lesson lesson : lessons) {
        Stream stream = lesson.getLessonInfo().getStream();
        lessonsByStream.computeIfAbsent(stream, k -> new ArrayList<>()).add(lesson);
      }

      // For each stream
      for (Map.Entry<Stream, List<Lesson>> entry : lessonsByStream.entrySet()) {
          Stream stream = entry.getKey();
          List<Lesson> streamLessons = entry.getValue();
          System.out.println(String.format("Processing stream %d with %d lessons", stream.getId(), streamLessons.size()));

          // For each day
          for (int currentDay = 0; currentDay < D; currentDay++) {
              final int day = currentDay;  // Create a final copy for lambda
              // Get valid slots for this day
              List<Integer> dayValidSlots = validClassSlots.stream()
                  .filter(slot -> slot / T == day)
                  .collect(Collectors.toList());

              // For each timeslot in this day
              for (int timeslot : dayValidSlots) {
                  List<BoolVar> lessonsInSlot = new ArrayList<>();
                  
                  // Create variables for each lesson that could be in this slot
                  for (Lesson lesson : streamLessons) {
                      boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                      
                      // Skip invalid slots for double lessons
                      if (isDouble) {
                          if ((timeslot % T) == T - 1) continue; // Skip last slot of day
                          if (!validClassSlots.contains(timeslot + 1)) continue;
                      }

                      // Create a boolean variable indicating if this lesson is in this slot
                      BoolVar isInSlot = model.newBoolVar(
                          String.format("stream_%d_lesson_%d_in_slot_%d", 
                              stream.getId(), lesson.getId(), timeslot));
                      
                      // Link the boolean variable to the actual lesson slot
                      model.addEquality(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot);
                      model.addDifferent(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot.not());
                      
                      lessonsInSlot.add(isInSlot);

                      // For double lessons, handle second slot
                      if (isDouble && validClassSlots.contains(timeslot + 1)) {
                          IntVar secondSlot = doubleLessonSecondSlots.get(lesson);
                          model.addEquality(secondSlot, timeslot + 1).onlyEnforceIf(isInSlot);
                      }
                  }

                  // Ensure at most one lesson in this timeslot for this stream
                  if (!lessonsInSlot.isEmpty()) {
                      model.addAtMostOne(lessonsInSlot.toArray(new Literal[0]));
                      System.out.println(String.format(
                          "Added constraint: At most one lesson for stream %d in timeslot %d (day %d)", 
                          stream.getId(), timeslot % T, day + 1));
                  }
              }

              // Add constraint to ensure even distribution across days
              List<BoolVar> lessonsOnDay = new ArrayList<>();
              for (Lesson lesson : streamLessons) {
                  BoolVar isOnDay = model.newBoolVar(
                      String.format("stream_%d_lesson_%d_on_day_%d", 
                          stream.getId(), lesson.getId(), day));
                  
                  // Create variables for each valid slot in this day
                  List<BoolVar> slotVars = new ArrayList<>();
                  for (int timeslot : dayValidSlots) {
                      boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                      
                      // Skip invalid slots for double lessons
                      if (isDouble) {
                          if ((timeslot % T) == T - 1) continue;
                          if (!validClassSlots.contains(timeslot + 1)) continue;
                      }

                      BoolVar isInSlot = model.newBoolVar(
                          String.format("stream_%d_lesson_%d_in_slot_%d", 
                              stream.getId(), lesson.getId(), timeslot));
                      
                      model.addEquality(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot);
                      model.addDifferent(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot.not());
                      slotVars.add(isInSlot);
                  }

                  // Lesson is on this day if it's in any of the day's slots
                  if (!slotVars.isEmpty()) {
                      model.addBoolOr(slotVars.toArray(new Literal[0])).onlyEnforceIf(isOnDay);
                      model.addBoolAnd(slotVars.stream().map(v -> v.not()).collect(Collectors.toList())
                          .toArray(new Literal[0])).onlyEnforceIf(isOnDay.not());
                      lessonsOnDay.add(isOnDay);
                  }
              }

              // Add soft constraint to encourage even distribution
              if (!lessonsOnDay.isEmpty()) {
                  int targetLessonsPerDay = (int) Math.ceil(streamLessons.size() / (double) D);
                  IntVar sumLessons = model.newIntVar(0, lessonsOnDay.size(), 
                      String.format("sum_lessons_stream_%d_day_%d", stream.getId(), day));
                  model.addEquality(sumLessons, LinearExpr.sum(lessonsOnDay.toArray(new IntVar[0])));
                  model.addLessOrEqual(sumLessons, targetLessonsPerDay + 1);
                  model.addGreaterOrEqual(sumLessons, targetLessonsPerDay - 1);
              }
        }
      }
    }

    private void enforceDoubleLessonConstraints() {
      // For each double lesson, ensure its second slot is properly constrained
      for (Map.Entry<Lesson, IntVar> entry : lessonSlots.entrySet()) {
        Lesson lesson = entry.getKey();

        if (lesson.getLessonInfo().getLessonType().equals(LessonType.DOUBLE)) {
          IntVar firstSlot = entry.getValue();
          IntVar secondSlot = doubleLessonSecondSlots.get(lesson);
          
          // Ensure first slot is not in the last timeslot of the day
          for (int day = 0; day < D; day++) {
            int lastSlotOfDay = (day + 1) * T - 1;
            model.addDifferent(firstSlot, model.newConstant(lastSlotOfDay));
          }
          
          // Only prevent lessons from the same stream being scheduled in the second slot
          Stream currentStream = lesson.getLessonInfo().getStream();
          for (Lesson otherLesson : lessons) {
              if (otherLesson != lesson && otherLesson.getLessonInfo().getStream().equals(currentStream)) {
                  model.addDifferent(lessonSlots.get(otherLesson), secondSlot);
            }
          }
        }
      }
    }

    private void oneLessonPerTimeslotPerStreamPerDay() {
      System.out.println("\nEnforcing lesson scheduling constraints");
      System.out.println("--------------------------------------");

      // Group lessons by stream
      Map<Stream, List<Lesson>> lessonsByStream = new HashMap<>();
      for (Lesson lesson : lessons) {
          Stream stream = lesson.getLessonInfo().getStream();
          lessonsByStream.computeIfAbsent(stream, k -> new ArrayList<>()).add(lesson);
      }

      // For each stream
      for (Map.Entry<Stream, List<Lesson>> entry : lessonsByStream.entrySet()) {
          Stream stream = entry.getKey();
          List<Lesson> streamLessons = entry.getValue();

          // For each day
          for (int currentDay = 0; currentDay < D; currentDay++) {
              final int day = currentDay;  // Create a final copy for lambda
              // Get valid slots for this day
              List<Integer> dayValidSlots = validClassSlots.stream()
                  .filter(slot -> slot / T == day)
                  .collect(Collectors.toList());

              // For each timeslot in this day
              for (int timeslot : dayValidSlots) {
                  List<BoolVar> lessonsInSlot = new ArrayList<>();
                  
                  // Create variables for each lesson that could be in this slot
                  for (Lesson lesson : streamLessons) {
                      boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                      
                      // Skip invalid slots for double lessons
                      if (isDouble) {
                          if ((timeslot % T) == T - 1) continue; // Skip last slot of day
                          if (!validClassSlots.contains(timeslot + 1)) continue;
                      }

                      BoolVar isInSlot = model.newBoolVar("lesson_" + lesson.getId() + "_in_slot_" + timeslot);
                      model.addEquality(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot);
                      model.addDifferent(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot.not());
                      lessonsInSlot.add(isInSlot);

                      // For double lessons, handle second slot
                      if (isDouble && validClassSlots.contains(timeslot + 1)) {
                          IntVar secondSlot = doubleLessonSecondSlots.get(lesson);
                          model.addEquality(secondSlot, timeslot + 1).onlyEnforceIf(isInSlot);
                      }
                  }

                  // Ensure at most one lesson in this timeslot for this stream
                  if (!lessonsInSlot.isEmpty()) {
                      model.addAtMostOne(lessonsInSlot.toArray(new Literal[0]));
                      System.out.println(String.format("Added constraint: At most one lesson for stream %d in timeslot %d", stream.getId(), timeslot));
                  }
              }

              // Add constraint to ensure even distribution across days
              List<BoolVar> lessonsOnDay = new ArrayList<>();
              for (Lesson lesson : streamLessons) {
                  BoolVar isOnDay = model.newBoolVar("lesson_" + lesson.getId() + "_on_day_" + day);
                  
                  // Create variables for each valid slot in this day
                  List<BoolVar> slotVars = new ArrayList<>();
                  for (int timeslot : dayValidSlots) {
                      boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                      
                      // Skip invalid slots for double lessons
                      if (isDouble) {
                          if ((timeslot % T) == T - 1) continue;
                          if (!validClassSlots.contains(timeslot + 1)) continue;
                      }

                      BoolVar isInSlot = model.newBoolVar("lesson_" + lesson.getId() + "_in_slot_" + timeslot);
                      model.addEquality(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot);
                      model.addDifferent(lessonSlots.get(lesson), timeslot).onlyEnforceIf(isInSlot.not());
                      slotVars.add(isInSlot);
                  }
                  
                  // isOnDay is true if lesson is in any slot of this day
                  if (!slotVars.isEmpty()) {
                      model.addMaxEquality(isOnDay, slotVars.toArray(new Literal[0]));
                      lessonsOnDay.add(isOnDay);
                  }
              }

              // Add soft constraint to encourage even distribution
              if (!lessonsOnDay.isEmpty()) {
                  // Calculate average lessons per day for this stream
                  double avgLessonsPerDay = (double) streamLessons.size() / D;
                  int maxLessonsPerDay = (int) Math.ceil(avgLessonsPerDay + 1);
                  
                  // Add constraint to limit lessons per day
                  model.addLinearConstraint(
                      LinearExpr.sum(lessonsOnDay.toArray(new BoolVar[0])), 
                      0, maxLessonsPerDay);
                  
                  System.out.println(String.format(
                      "Added constraint: Stream %d can have at most %d lessons on day %d",
                      stream.getId(), maxLessonsPerDay, day + 1));
              }
          }
      }
  }

    private void applyCombinedSubjectsConstraint() {
      // Group lessons by combined subject and intake
          Map<String, List<Lesson>> groupedLessons = lessons.stream()
                  .filter(lesson -> lesson.getLessonInfo().getCombinedSubject() != null)
                  .collect(Collectors.groupingBy(lesson -> {
                      CombinedSubject subject = lesson.getLessonInfo().getCombinedSubject();
                      String key = (subject.getSubjectName() != null ? subject.getSubjectName() : "Unknown") 
                                  + "_" + subject.getSubjectCode() 
                                  + "_" + lesson.getLessonInfo().getStream().getIntake().getId();
                      return key;
                  }));
      
      // For each group of combined subject lessons
      for (Map.Entry<String, List<Lesson>> entry : groupedLessons.entrySet()) {
          List<Lesson> group = entry.getValue();

          
              if (group.size() > 1) {
              // Split the group into singles and doubles
              Map<Stream, List<Lesson>> singlesByStream = group.stream()
                  .filter(l -> l.getLessonInfo().getLessonType() == LessonType.SINGLE)
                  .collect(Collectors.groupingBy(l -> l.getLessonInfo().getStream()));
              
              Map<Stream, List<Lesson>> doublesByStream = group.stream()
                  .filter(l -> l.getLessonInfo().getLessonType() == LessonType.DOUBLE)
                  .collect(Collectors.groupingBy(l -> l.getLessonInfo().getStream()));

              // Verify that all streams have the same number of singles and doubles
              int singlesPerStream = singlesByStream.isEmpty() ? 0 : singlesByStream.values().iterator().next().size();
              int doublesPerStream = doublesByStream.isEmpty() ? 0 : doublesByStream.values().iterator().next().size();

              // Handle singles
              if (!singlesByStream.isEmpty()) {
                  // For each single lesson index (0 to singlesPerStream-1)
                  for (int lessonIndex = 0; lessonIndex < singlesPerStream; lessonIndex++) {
                      final int idx = lessonIndex;
                      
                      // Get the corresponding lesson from each stream
                      List<Lesson> correspondingSingles = singlesByStream.values().stream()
                          .map(streamLessons -> streamLessons.get(idx))
                          .collect(Collectors.toList());
      
                      // For each possible timeslot
                      for (int day = 0; day < D; day++) {
                          for (int slot = 0; slot < T; slot++) {
                              int timeslot = day * T + slot;
                              if (!validClassSlots.contains(timeslot)) continue;

                              // Create BoolVars for each corresponding single lesson
                              List<BoolVar> lessonInSlotVars = new ArrayList<>();
                              for (Lesson lesson : correspondingSingles) {
                                  IntVar lessonSlot = lessonSlots.get(lesson);
                                  BoolVar isInSlot = model.newBoolVar("combined_single_" + lesson.getId() + "_in_slot_" + timeslot);
                                  model.addEquality(lessonSlot, timeslot).onlyEnforceIf(isInSlot);
                                  model.addDifferent(lessonSlot, timeslot).onlyEnforceIf(isInSlot.not());
                                  lessonInSlotVars.add(isInSlot);
                              }

                              // Create a BoolVar indicating if this timeslot is used for this set of singles
                              BoolVar isSlotUsed = model.newBoolVar("singles_" + lessonIndex + "_slot_used_" + timeslot);
                              
                              // If any lesson is in this slot, all corresponding singles must also be in this slot
                              for (BoolVar isInSlot : lessonInSlotVars) {
                                  model.addEquality(isInSlot, isSlotUsed);
                              }
                          }
                      }
                  }
              }

              // Handle doubles
              if (!doublesByStream.isEmpty()) {
                  // For each double lesson index (0 to doublesPerStream-1)
                  for (int lessonIndex = 0; lessonIndex < doublesPerStream; lessonIndex++) {
                      final int idx = lessonIndex;
                      
                      // Get the corresponding lesson from each stream
                      List<Lesson> correspondingDoubles = doublesByStream.values().stream()
                          .map(streamLessons -> streamLessons.get(idx))
                          .collect(Collectors.toList());

                      // For each possible timeslot
                      for (int day = 0; day < D; day++) {
                          for (int slot = 0; slot < T; slot++) {
                              int timeslot = day * T + slot;
                              
                              // Skip invalid slots for doubles
                              if ((slot + 1) >= T) continue;
                              if (!validClassSlots.contains(timeslot) || !validClassSlots.contains(timeslot + 1)) continue;

                              // Create BoolVars for each corresponding double lesson
                              List<BoolVar> lessonInSlotVars = new ArrayList<>();
                              for (Lesson lesson : correspondingDoubles) {
                                  IntVar lessonSlot = lessonSlots.get(lesson);
                                  BoolVar isInSlot = model.newBoolVar("combined_double_" + lesson.getId() + "_in_slot_" + timeslot);
                                  model.addEquality(lessonSlot, timeslot).onlyEnforceIf(isInSlot);
                                  model.addDifferent(lessonSlot, timeslot).onlyEnforceIf(isInSlot.not());
                                  lessonInSlotVars.add(isInSlot);

                                  // Handle second slot
                                  IntVar secondSlot = doubleLessonSecondSlots.get(lesson);
                                  if (secondSlot != null) {
                                      model.addEquality(secondSlot, timeslot + 1).onlyEnforceIf(isInSlot);
                                      model.addDifferent(secondSlot, timeslot + 1).onlyEnforceIf(isInSlot.not());
                                  }
                              }

                              // Create a BoolVar indicating if this timeslot is used for this set of doubles
                              BoolVar isSlotUsed = model.newBoolVar("doubles_" + lessonIndex + "_slot_used_" + timeslot);
                              
                              // If any lesson is in this slot, all corresponding doubles must also be in this slot
                              for (BoolVar isInSlot : lessonInSlotVars) {
                                  model.addEquality(isInSlot, isSlotUsed);
                              }
                          }
                      }
                  }
              }

              // Ensure each lesson is scheduled exactly once
              for (Lesson lesson : group) {
                  IntVar lessonSlot = lessonSlots.get(lesson);
                  List<BoolVar> slotVars = new ArrayList<>();
                  
                  for (int timeslot : validClassSlots) {
                      // For double lessons, check if this can be a valid starting slot
                      if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE) {
                          if ((timeslot % T) == T - 1) continue; // Skip last slot of day
                          if (!validClassSlots.contains(timeslot + 1)) continue; // Skip if next slot isn't valid
                      }
                      
                      BoolVar isInSlot = model.newBoolVar("lesson_" + lesson.getId() + "_in_slot_" + timeslot);
                      model.addEquality(lessonSlot, timeslot).onlyEnforceIf(isInSlot);
                      model.addDifferent(lessonSlot, timeslot).onlyEnforceIf(isInSlot.not());
                      slotVars.add(isInSlot);
                  }
                  
                  model.addExactlyOne(slotVars.toArray(new Literal[0]));
              }
          } 
      }
  }

      //HARD CONSTRAINTS
      //Constraint 1: teachers not to be assigned a lesson when marked as unavailable
    private void enforceTeacherUnavailability() {
        // Map<TeacherId, Map<DayOfWeek, Set<TimeslotId>>>
        Map<Long, Map<DayOfWeek, Set<Long>>> teacherUnavailability = new HashMap<>();
        
        // Populate the unavailability map
        for (TeacherTimeOff timeOff : teacherTimeOffs) {
            Long teacherId = timeOff.getTeacher().getId();
            teacherUnavailability
                .computeIfAbsent(teacherId, k -> new HashMap<>())
                .computeIfAbsent(timeOff.getDayOfWeek(), k -> new HashSet<>())
                .add(timeOff.getTimeslot().getId());
        }

        System.out.println("Found unavailability records for " + teacherUnavailability.size() + " teachers");


        for (Lesson lesson : lessons) {
            boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
            Set<Long> teacherIds = lesson.getLessonInfo().getTeachers().stream()
                .map(Teacher::getId)
                .collect(Collectors.toSet());

            // For each teacher of this lesson
            for (Long teacherId : teacherIds) {
                Map<DayOfWeek, Set<Long>> teacherSchedule = teacherUnavailability.get(teacherId);
                if (teacherSchedule == null) continue; // Teacher has no unavailability records

                // For each day and timeslot
                for (int day = 0; day < D; day++) {
                    DayOfWeek currentDayOfWeek = DayOfWeek.of((day % 7) + 1);
                    Set<Long> unavailableSlots = teacherSchedule.get(currentDayOfWeek);
                    if (unavailableSlots == null) continue; // No unavailability on this day

                    for (int slot = 0; slot < T; slot++) {
                        Long timeslotId = schedule.getTimeslotList().get(slot).getId();
                        int globalSlot = day * T + slot;

                        if (isDouble) {
                            // For double lessons, check both the current slot and next slot
                            if (slot + 1 < T) {
                                Long nextTimeslotId = schedule.getTimeslotList().get(slot + 1).getId();
                                if (unavailableSlots.contains(timeslotId) || unavailableSlots.contains(nextTimeslotId)) {
                                    // Prevent lesson from starting in this slot
                                    model.addDifferent(lessonSlots.get(lesson), globalSlot);
                                }
                            } else {
                                // Last slot of day - can't start a double lesson here anyway
                                model.addDifferent(lessonSlots.get(lesson), globalSlot);
                            }
                        } else {
                            // For single lessons, just check the current slot
                            if (unavailableSlots.contains(timeslotId)) {
                                model.addDifferent(lessonSlots.get(lesson), globalSlot);
                              
                            }
                        }
                    }
                }
            }
        }
    }

      //Constraint 2: teacher teaches at most one lesson at the same time e.g. 8 - 8:40 am on a given day, teacher shouldn't be assigned one lesson in stream A and another one in stream B
      //Teacher cannot have more than one lesson at the same time across streams
    private void noOverlappingLessonAcrossStreams() {
          System.out.println("\nApplying Teacher Non-overlapping Constraints");
          System.out.println("------------------------------------------");

          for (int day = 0; day < D; day++) {
              for (int slot = 0; slot < T; slot++) {
                  int globalSlot = day * T + slot;
                  
                  if (!validClassSlots.contains(globalSlot)) 
                      continue;

                  // Group lessons by teacher for this timeslot
                  Map<Teacher, List<BoolVar>> teacherAssignments = new HashMap<>();
    
                  for (Lesson lesson : lessons) {
                      Set<Teacher> lessonTeachers = new HashSet<>(lesson.getLessonInfo().getTeachers());
                      if (lessonTeachers.isEmpty()) 
                          continue;

                      boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                      
                      // For double lessons, check if this is a valid starting slot
                      if (isDouble) {
                          // Skip last slot of day
                          if (slot == T - 1) 
                              continue; 

                          //skip if next slot is not valid
                          int nextGlobalSlot = globalSlot + 1;
                          if (!validClassSlots.contains(nextGlobalSlot)) 
                            continue;
                      }

                      // Create a boolean variable indicating if this lesson starts in the current slot
                      BoolVar isAssigned = model.newBoolVar(String.format("teacher_assigned_%d_day%d_slot%d", lesson.getId(), day, slot));
                      model.addEquality(lessonSlots.get(lesson), globalSlot).onlyEnforceIf(isAssigned);
                      model.addDifferent(lessonSlots.get(lesson), globalSlot).onlyEnforceIf(isAssigned.not());

                      // Add this assignment to all teachers of this lesson
                      for (Teacher teacher : lessonTeachers) {
                          teacherAssignments.computeIfAbsent(teacher, k -> new ArrayList<>()).add(isAssigned);
                          
                          // For double lessons, also prevent the teacher from being assigned 
                          // to any lesson in the second slot
                          if (isDouble && slot < T - 1) {
                              for (Lesson otherLesson : lessons) {
                                  if (otherLesson.getLessonInfo().getTeachers().contains(teacher)) {
                                      // Prevent other lesson from starting in the next slot if this lesson is assigned
                                      model.addDifferent(lessonSlots.get(otherLesson), globalSlot + 1)
                                           .onlyEnforceIf(isAssigned);
                                  }
                              }
                          }
                      }
                  }
    
                  // Ensure each teacher teaches at most one lesson per timeslot
                  for (Map.Entry<Teacher, List<BoolVar>> entry : teacherAssignments.entrySet()) {
                      List<BoolVar> assignedLessons = entry.getValue();
                      if (assignedLessons.size() > 1) {
                          model.addAtMostOne(assignedLessons.toArray(new Literal[0]));
                      }
                  }
              }
          }
      }

    //Constraint 3: subjects with 5 or fewer lessons per week per stream to be scheduled once per day to ensure even distribution throughout the week
    private void lessThanFiveLessonsPerWeekPerStream() {
          System.out.println("\nApplying Constraint: At most one lesson per day for subjects with ≤5 lessons/week");
          System.out.println("------------------------------------------------------------------------");

          // Group lessons by stream and subject/combinedSubject
          Map<Stream, Map<Object, List<Lesson>>> lessonsByStreamAndSubject = new HashMap<>();
          
    for (Lesson lesson : lessons) {
        Stream stream = lesson.getLessonInfo().getStream();
              Object subjectKey;
              
              if (lesson.getLessonInfo().getSubject() != null) {
                  subjectKey = lesson.getLessonInfo().getSubject().getId();
              } else if (lesson.getLessonInfo().getCombinedSubject() != null) {
                  subjectKey = "combined_" + lesson.getLessonInfo().getCombinedSubject().getId();
              } else {
                  continue; // Skip if no subject
              }
              
              lessonsByStreamAndSubject
                  .computeIfAbsent(stream, k -> new HashMap<>())
                  .computeIfAbsent(subjectKey, k -> new ArrayList<>())
                  .add(lesson);
          }

          int constraintsAdded = 0;

          // For each stream and subject combination
          for (Map.Entry<Stream, Map<Object, List<Lesson>>> streamEntry : lessonsByStreamAndSubject.entrySet()) {
              Stream stream = streamEntry.getKey();
              
              for (Map.Entry<Object, List<Lesson>> subjectEntry : streamEntry.getValue().entrySet()) {
                  List<Lesson> subjectLessons = subjectEntry.getValue();
                  
                  // Calculate total lessons per week (counting doubles as one lesson)
                  int lessonsPerWeek = subjectLessons.stream()
                      .map(lesson -> lesson.getLessonInfo())
                      .collect(Collectors.groupingBy(
                          LessonInfo::getLessonType,
                          Collectors.counting()
                      ))
                      .entrySet().stream()
                      .mapToInt(entry -> entry.getValue().intValue())
                      .sum();

                  // Only apply constraint if subject has 5 or fewer lessons per week
                  if (lessonsPerWeek <= 5) {
                      String subjectName = subjectEntry.getKey().toString();
        System.out.println(String.format(
                          "Stream %d, Subject %s: %d lessons/week - applying one-per-day constraint",
                          stream.getId(), subjectName, lessonsPerWeek
                      ));

                      for (int currentDay = 0; currentDay < D; currentDay++) {
                          final int day = currentDay;
                          List<BoolVar> lessonInDayVars = new ArrayList<>();
                          
                          // Get valid slots for this day
            List<Integer> dayValidSlots = validClassSlots.stream()
                              .filter(slot -> slot / T == day)
                .collect(Collectors.toList());

                          // For each lesson of this subject
                          for (Lesson lesson : subjectLessons) {
                              // Create a boolean variable for if this lesson is scheduled on this day
                              BoolVar lessonOnDay = model.newBoolVar(
                                  String.format("lesson_%d_on_day_%d", lesson.getId(), day)
                              );
                              
                              // If lesson is scheduled in any slot of this day, lessonOnDay should be true
                List<BoolVar> slotVars = new ArrayList<>();
                              for (int globalSlot : dayValidSlots) {
                                  BoolVar isInSlot = model.newBoolVar(
                                      String.format("lesson_%d_in_slot_%d", lesson.getId(), globalSlot)
                                  );
                                  model.addEquality(lessonSlots.get(lesson), globalSlot).onlyEnforceIf(isInSlot);
                                  model.addDifferent(lessonSlots.get(lesson), globalSlot).onlyEnforceIf(isInSlot.not());
                                  slotVars.add(isInSlot);
                              }
                              
                              // lessonOnDay is true if lesson is in any slot of this day
                              if (!slotVars.isEmpty()) {
                                  model.addMaxEquality(lessonOnDay, slotVars.toArray(new Literal[0]));
                                  lessonInDayVars.add(lessonOnDay);
                              }
                          }
                          
                          // For this stream, subject, and day, ensure:
                          // - Either at most 2 single lessons OR at most 1 double lesson (but not both)
                          if (!lessonInDayVars.isEmpty()) {
                              // Split into singles and doubles
                              List<BoolVar> singleVars = new ArrayList<>();
                              List<BoolVar> doubleVars = new ArrayList<>();
                              
                              for (int i = 0; i < subjectLessons.size(); i++) {
                                  Lesson lesson = subjectLessons.get(i);
                                  BoolVar lessonVar = lessonInDayVars.get(i);
                                  
                                  if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE) {
                                      doubleVars.add(lessonVar);
                                  } else {
                                      singleVars.add(lessonVar);
                                  }
                              }

                              if (!singleVars.isEmpty() && !doubleVars.isEmpty()) {
                                  // Create boolean variables to indicate which type is used
                                  BoolVar useSingles = model.newBoolVar(
                                      String.format("use_singles_stream_%d_subject_%s_day_%d", 
                                          stream.getId(), subjectEntry.getKey().toString(), day));
                                  BoolVar useDoubles = model.newBoolVar(
                                      String.format("use_doubles_stream_%d_subject_%s_day_%d", 
                                          stream.getId(), subjectEntry.getKey().toString(), day));

                                  // Only one type can be used
                                  model.addAtMostOne(new BoolVar[] {useSingles, useDoubles});

                                  // Add constraints for singles
                                  LinearExpr singleSum = LinearExpr.sum(singleVars.toArray(new BoolVar[0]));
                                  model.addLinearConstraint(singleSum, 0, 2);
                                  
                                  // Add constraints for doubles
                                  LinearExpr doubleSum = LinearExpr.sum(doubleVars.toArray(new BoolVar[0]));
                                  model.addLinearConstraint(doubleSum, 0, 1);
                                  
                                  // Link the sums to the type indicators
                                  model.addEquality(singleSum, 0).onlyEnforceIf(useDoubles);
                                  model.addEquality(doubleSum, 0).onlyEnforceIf(useSingles);

                                  constraintsAdded++;
                              } else if (!singleVars.isEmpty()) {
                                  // Only singles available - limit to at most 2
                                  model.addLinearConstraint(
                                      LinearExpr.sum(singleVars.toArray(new BoolVar[0])), 
                                      0, 2);
                                  constraintsAdded++;
                              } else if (!doubleVars.isEmpty()) {
                                  // Only doubles available - limit to at most 1
                                  model.addLinearConstraint(
                                      LinearExpr.sum(doubleVars.toArray(new BoolVar[0])), 
                                      0, 1);
                                  constraintsAdded++;
                              }
                          }
                      }
                  }
              }
          }

          System.out.println("Added " + constraintsAdded + " one-lesson-per-day constraints");
      }
      

     //Constraint 4: For a given intake, combined streams to be taught the subject for which they are combined at the same time
     //Can only combine streams that belong to the same intake
    private void applyCombinedStreamsConstraint() {
          System.out.println("\nApplying Combined Streams Constraints");
          System.out.println("------------------------------------");
          
          int constraintsAdded = 0;

          // Group CombinedStreams by subject 
          Map<Subject, List<CombinedStream>> streamsBySubject = combinedStreams.stream()
              .collect(Collectors.groupingBy(CombinedStream::getSubject));

          for (Map.Entry<Subject, List<CombinedStream>> entry : streamsBySubject.entrySet()) {
              Subject subject = entry.getKey();
              List<CombinedStream> combinedStreamGroup = entry.getValue();

              for (CombinedStream combinedStream : combinedStreamGroup) {
                  Set<Stream> constituentStreams = combinedStream.getConstituentStreams();
                  
                  // Get all lessons for this subject and these streams
                  List<Lesson> relevantLessons = lessons.stream()
                      .filter(lesson -> 
                          // Check if lesson is for one of the constituent streams
                          constituentStreams.contains(lesson.getLessonInfo().getStream()) &&
                          // Check if lesson is for the correct subject
                          lesson.getLessonInfo().getSubject() != null &&
                          lesson.getLessonInfo().getSubject().equals(subject)
                      )
                      .collect(Collectors.toList());

                  // Group lessons by type (SINGLE/DOUBLE) and stream
                  Map<LessonType, Map<Stream, List<Lesson>>> lessonsByTypeAndStream = relevantLessons.stream()
                      .collect(Collectors.groupingBy(
                          lesson -> lesson.getLessonInfo().getLessonType(),
                          Collectors.groupingBy(lesson -> lesson.getLessonInfo().getStream())
                      ));

                  // Process each lesson type separately
                  for (Map.Entry<LessonType, Map<Stream, List<Lesson>>> typeEntry : lessonsByTypeAndStream.entrySet()) {
                      LessonType lessonType = typeEntry.getKey();
                      Map<Stream, List<Lesson>> streamLessons = typeEntry.getValue();

                      // Verify all streams have the same number of lessons
                      Iterator<List<Lesson>> iterator = streamLessons.values().iterator();
                      if (!iterator.hasNext()) continue;
                      
                      int expectedCount = iterator.next().size();
                      boolean validCount = streamLessons.values().stream()
                          .allMatch(list -> list.size() == expectedCount);

                      if (!validCount) {
                          System.out.println(String.format(
                              "Warning: Uneven number of %s lessons for subject %s across streams",
                              lessonType, subject
                          ));
                          continue;
                      }

                      // Create lesson groups (one lesson from each stream)
                      for (int i = 0; i < expectedCount; i++) {
                          final int lessonIndex = i;
                          List<Lesson> lessonGroup = streamLessons.values().stream()
                              .map(lessons -> lessons.get(lessonIndex))
                              .collect(Collectors.toList());

                          // All lessons in the group must be scheduled at the same time
                          IntVar firstSlot = lessonSlots.get(lessonGroup.get(0));
                          for (int j = 1; j < lessonGroup.size(); j++) {
                              model.addEquality(lessonSlots.get(lessonGroup.get(j)), firstSlot);
                              constraintsAdded++;
                          }

                          // For double lessons, ensure second slots are also aligned
                          if (lessonType == LessonType.DOUBLE) {
                              IntVar firstSecondSlot = doubleLessonSecondSlots.get(lessonGroup.get(0));
                              for (int j = 1; j < lessonGroup.size(); j++) {
                                  model.addEquality(doubleLessonSecondSlots.get(lessonGroup.get(j)), firstSecondSlot);
                                  constraintsAdded++;
                              }
                          }
                      }
                  }
              }
          }

          System.out.println("Added " + constraintsAdded + " combined streams constraints");
      }


       //Constraint 5: Hard subjects taught at most twice a day - English, Kiswahili, Maths
      //No more than 2 single lessons a day/no more than one double lesson a day
   private void limitCoreSubjectsPerDay() {
          System.out.println("\nApplying Core Subjects Per Day Constraint");
          System.out.println("----------------------------------------");
          
          int constraintsAdded = 0;
          Set<Integer> coreSubjectCodes = Set.of(ENGLISH_CODE, KISWAHILI_CODE, MATH_CODE);

          // First group lessons by stream and subject
          Map<Stream, Map<Long, List<Lesson>>> lessonsByStreamAndSubject = lessons.stream()
              .filter(lesson -> 
                  lesson.getLessonInfo().getSubject() != null && 
                  coreSubjectCodes.contains(lesson.getLessonInfo().getSubject().getIntCode())
              )
              .collect(Collectors.groupingBy(
                  lesson -> lesson.getLessonInfo().getStream(),
                  Collectors.groupingBy(lesson -> lesson.getLessonInfo().getSubject().getId())
              ));

          // For each stream
          for (Map.Entry<Stream, Map<Long, List<Lesson>>> streamEntry : lessonsByStreamAndSubject.entrySet()) {
              Stream stream = streamEntry.getKey();
              Map<Long, List<Lesson>> subjectLessons = streamEntry.getValue();

              // For each core subject
              for (Map.Entry<Long, List<Lesson>> subjectEntry : subjectLessons.entrySet()) {
                  Long subjectId = subjectEntry.getKey();
                  List<Lesson> lessonsForSubject = subjectEntry.getValue();

                  System.out.println(String.format(
                      "Processing stream %d, subject %d: Found %d lessons",
                      stream.getId(), subjectId, lessonsForSubject.size()
                  ));

                  // For each day
                  for (int currentDay = 0; currentDay < D; currentDay++) {
                      final int day = currentDay;
                      List<BoolVar> lessonInDayVars = new ArrayList<>();
                      
                      // Get valid slots for this day
                      List<Integer> dayValidSlots = validClassSlots.stream()
                          .filter(slot -> slot / T == day)
                          .collect(Collectors.toList());
                          
                      // For each lesson of this subject
                      for (Lesson lesson : lessonsForSubject) {
                    boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                    
                          // Create a boolean variable for if this lesson is scheduled on this day
                          BoolVar lessonOnDay = model.newBoolVar(
                              String.format("core_lesson_%d_on_day_%d", lesson.getId(), day)
                          );
                          
                          // For each valid slot in this day
                          List<BoolVar> slotVars = new ArrayList<>();
                          for (int globalSlot : dayValidSlots) {
                    // Skip invalid slots for double lessons
                    if (isDouble) {
                                  if (globalSlot % T == T - 1) continue; // Skip last slot of day
                                  if (!validClassSlots.contains(globalSlot + 1)) continue;
                    }

                    BoolVar isInSlot = model.newBoolVar(
                                  String.format("core_lesson_%d_in_slot_%d", lesson.getId(), globalSlot)
                              );
                              model.addEquality(lessonSlots.get(lesson), globalSlot).onlyEnforceIf(isInSlot);
                              model.addDifferent(lessonSlots.get(lesson), globalSlot).onlyEnforceIf(isInSlot.not());
                    slotVars.add(isInSlot);
                }

                          // lessonOnDay is true if lesson is in any slot of this day
                if (!slotVars.isEmpty()) {
                              model.addMaxEquality(lessonOnDay, slotVars.toArray(new Literal[0]));
                              lessonInDayVars.add(lessonOnDay);
                          }
                      }
                      
                      // For this stream, subject, and day, ensure:
                      // - Either at most 2 single lessons OR at most 1 double lesson (but not both)
                      if (!lessonInDayVars.isEmpty()) {
                          // Split into singles and doubles
                          List<BoolVar> singleVars = new ArrayList<>();
                          List<BoolVar> doubleVars = new ArrayList<>();
                          
                          for (int i = 0; i < lessonsForSubject.size(); i++) {
                              Lesson lesson = lessonsForSubject.get(i);
                              BoolVar lessonVar = lessonInDayVars.get(i);
                              
                              if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE) {
                                  doubleVars.add(lessonVar);
                              } else {
                                  singleVars.add(lessonVar);
                              }
                          }

                          if (!singleVars.isEmpty() && !doubleVars.isEmpty()) {
                              // Create boolean variables to indicate which type is used
                              BoolVar useSingles = model.newBoolVar(
                                  String.format("use_singles_stream_%d_subject_%d_day_%d", 
                                      stream.getId(), subjectId, day));
                              BoolVar useDoubles = model.newBoolVar(
                                  String.format("use_doubles_stream_%d_subject_%d_day_%d", 
                                      stream.getId(), subjectId, day));

                              // Only one type can be used
                              model.addAtMostOne(new BoolVar[] {useSingles, useDoubles});

                              // Add constraints for singles
                              LinearExpr singleSum = LinearExpr.sum(singleVars.toArray(new BoolVar[0]));
                              model.addLinearConstraint(singleSum, 0, 2);
                              
                              // Add constraints for doubles
                              LinearExpr doubleSum = LinearExpr.sum(doubleVars.toArray(new BoolVar[0]));
                              model.addLinearConstraint(doubleSum, 0, 1);
                              
                              // Link the sums to the type indicators
                              model.addEquality(singleSum, 0).onlyEnforceIf(useDoubles);
                              model.addEquality(doubleSum, 0).onlyEnforceIf(useSingles);

                              constraintsAdded++;
                          } else if (!singleVars.isEmpty()) {
                              // Only singles available - limit to at most 2
                              model.addLinearConstraint(
                                  LinearExpr.sum(singleVars.toArray(new BoolVar[0])), 
                                  0, 2);
                              constraintsAdded++;
                          } else if (!doubleVars.isEmpty()) {
                              // Only doubles available - limit to at most 1
                              model.addLinearConstraint(
                                  LinearExpr.sum(doubleVars.toArray(new BoolVar[0])), 
                                  0, 1);
                              constraintsAdded++;
                          }
                      }
                  }
              }
          }

          System.out.println("Added " + constraintsAdded + " core subjects per day constraints");
      }

  //SOFT CONSTRAINTS
   private void implementPELessonsNearBreaks() {
          pePenaltyVars = new ArrayList<>();
          
          // For each lesson
          for (Lesson lesson : lessons) {
              if (lesson.getLessonInfo().getSubject() != null && 
                  "P.E".equals(lesson.getLessonInfo().getSubject().getName())) {
                  
                  IntVar lessonSlot = lessonSlots.get(lesson);
                  boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                  IntVar secondSlot = isDouble ? doubleLessonSecondSlots.get(lesson) : null;
                  
                  // For each day
                  for (int day = 0; day < D; day++) {
                      List<Integer> breakSlots = new ArrayList<>();
                      
                      // Find break slots for this day
                      for (int slot = 0; slot < T; slot++) {
                          TimeslotActivity activity = schedule.getTimeslotList().get(slot).getTimeslotActivity();
                          if (activity == TimeslotActivity.SHORTBREAK || 
                              activity == TimeslotActivity.LONGBREAK || 
                              activity == TimeslotActivity.LUNCHBREAK) {
                              breakSlots.add(day * T + slot);
                          }
                      }
                      
                      if (!breakSlots.isEmpty()) {
                          // For each break slot, create variables to measure distance
                          List<IntVar> distanceVars = new ArrayList<>();
                          
                          for (int breakSlot : breakSlots) {
                              // Create variables for the absolute difference between lesson slot and break slot
                              IntVar distance = model.newIntVar(0, T, "pe_dist_" + lesson.getId() + "_" + breakSlot);
                              IntVar diff = model.newIntVar(-T, T, "pe_diff_" + lesson.getId() + "_" + breakSlot);
                              
                              // Calculate absolute difference
                              model.addEquality(diff, LinearExpr.newBuilder()
                                  .add(lessonSlot)
                                  .add(-breakSlot)
                                  .build());
                              model.addAbsEquality(distance, diff);
                              
                              distanceVars.add(distance);

                              // If this is a double lesson, also check distance from second slot
                              if (isDouble) {
                                  IntVar distance2 = model.newIntVar(0, T, "pe_dist2_" + lesson.getId() + "_" + breakSlot);
                                  IntVar diff2 = model.newIntVar(-T, T, "pe_diff2_" + lesson.getId() + "_" + breakSlot);
                                  
                                  model.addEquality(diff2, LinearExpr.newBuilder()
                                      .add(secondSlot)
                                      .add(-breakSlot)
                                      .build());
                                  model.addAbsEquality(distance2, diff2);
                                  
                                  distanceVars.add(distance2);
                              }
                          }
                          
                          // Create a variable for the minimum distance to any break
                          IntVar minDistance = model.newIntVar(0, T, "pe_min_dist_" + lesson.getId() + "_day_" + day);
                          model.addMinEquality(minDistance, distanceVars.toArray(new IntVar[0]));
                          
                          // Add to penalty variables
                          pePenaltyVars.add(minDistance);
                      }
                  }
              }
          }
          
          System.out.println("Added PE lessons near breaks soft constraint with " + pePenaltyVars.size() + " penalty variables");
      }

   private void implementMathScienceNonAdjacency() {
          mathSciencePenaltyVars = new ArrayList<>();
          
          // Group lessons by stream
          Map<Stream, List<Lesson>> lessonsByStream = lessons.stream()
              .collect(Collectors.groupingBy(lesson -> lesson.getLessonInfo().getStream()));
              
          for (Map.Entry<Stream, List<Lesson>> entry : lessonsByStream.entrySet()) {
              final Stream stream = entry.getKey();
              List<Lesson> streamLessons = entry.getValue();
              
              // For each day
              for (int day = 0; day < D; day++) {
                  final int currentDay = day;  // Create a final copy for lambda
                  // Get valid slots for this day
                  List<Integer> daySlots = validClassSlots.stream()
                      .filter(slot -> slot / T == currentDay)
                      .collect(Collectors.toList());
                      
                  // For each slot in the day (except last)
                  for (int i = 0; i < daySlots.size() - 1; i++) {
                      int currentSlot = daySlots.get(i);
                      int nextSlot = daySlots.get(i + 1);
                      
                      // For each pair of lessons
                      for (Lesson lesson1 : streamLessons) {
                          for (Lesson lesson2 : streamLessons) {
                              if (lesson1 == lesson2) continue;
                              
                              // Check if lessons are Math and Science or vice versa
                              boolean isMathScience = false;
                              if (lesson1.getLessonInfo().getSubject() != null && 
                                  lesson2.getLessonInfo().getSubject() != null) {
                                  
                                  String category1Name = lesson1.getLessonInfo().getSubject().getCategory().getName();
                                  String category2Name = lesson2.getLessonInfo().getSubject().getCategory().getName();
                                  
                                  isMathScience = (MATHEMATICS_CATEGORY.equals(category1Name) && SCIENCES_CATEGORY.equals(category2Name)) ||
                                                 (SCIENCES_CATEGORY.equals(category1Name) && MATHEMATICS_CATEGORY.equals(category2Name));
                              }
                              
                              if (isMathScience) {
                                  boolean isDouble1 = lesson1.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                                  boolean isDouble2 = lesson2.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                                  
                                  // Create penalty variable for this pair
                                  BoolVar isAdjacent = model.newBoolVar(
                                      String.format("mathsci_adjacent_%d_%d_day%d_slot%d", 
                                          lesson1.getId(), lesson2.getId(), day, i));
                                  
                                  // Get lesson slot variables
                                  IntVar slot1 = lessonSlots.get(lesson1);
                                  IntVar slot2 = lessonSlots.get(lesson2);
                                  
                                  if (isDouble1) {
                                      // If lesson1 is double, check if lesson2 starts right after lesson1's second slot
                                      IntVar secondSlot1 = doubleLessonSecondSlots.get(lesson1);
                                      model.addEquality(slot1, currentSlot).onlyEnforceIf(isAdjacent);
                                      model.addEquality(secondSlot1, nextSlot - 1).onlyEnforceIf(isAdjacent);
                                      model.addEquality(slot2, nextSlot).onlyEnforceIf(isAdjacent);
                                  } else if (isDouble2) {
                                      // If lesson2 is double, check if it starts right after lesson1
                                      model.addEquality(slot1, currentSlot).onlyEnforceIf(isAdjacent);
                                      model.addEquality(slot2, nextSlot).onlyEnforceIf(isAdjacent);
        } else {
                                      // Both are single lessons
                                      model.addEquality(slot1, currentSlot).onlyEnforceIf(isAdjacent);
                                      model.addEquality(slot2, nextSlot).onlyEnforceIf(isAdjacent);
                                  }
                                  
                                  mathSciencePenaltyVars.add(isAdjacent);
                              }
                          }
                      }
                  }
              }
          }
          
          System.out.println("Added Math-Science non-adjacency constraint with " + 
              mathSciencePenaltyVars.size() + " penalty variables");
      }

   private void implementEnglishKiswahiliNonAdjacency() {
          englishKiswahiliPenaltyVars = new ArrayList<>();
          
          // Group lessons by stream
          Map<Stream, List<Lesson>> lessonsByStream = lessons.stream()
              .collect(Collectors.groupingBy(lesson -> lesson.getLessonInfo().getStream()));
              
          for (Map.Entry<Stream, List<Lesson>> entry : lessonsByStream.entrySet()) {
              Stream stream = entry.getKey();
              List<Lesson> streamLessons = entry.getValue();
              
              // For each day
              for (int day = 0; day < D; day++) {
                  final int currentDay = day;
                  // Get valid slots for this day
                  List<Integer> daySlots = validClassSlots.stream()
                      .filter(slot -> slot / T == currentDay)
                      .collect(Collectors.toList());
                      
                  // For each slot in the day (except last)
                  for (int i = 0; i < daySlots.size() - 1; i++) {
                      int currentSlot = daySlots.get(i);
                      int nextSlot = daySlots.get(i + 1);
                      
                      // For each pair of lessons
                      for (Lesson lesson1 : streamLessons) {
                          for (Lesson lesson2 : streamLessons) {
                              if (lesson1 == lesson2) continue;
                              
                              // Check if lessons are English and Kiswahili or vice versa
                              boolean isEnglishKiswahili = false;
                              if (lesson1.getLessonInfo().getSubject() != null && 
                                  lesson2.getLessonInfo().getSubject() != null) {
                                  
                                  int subject1Code = lesson1.getLessonInfo().getSubject().getIntCode();
                                  int subject2Code = lesson2.getLessonInfo().getSubject().getIntCode();
                                  
                                  isEnglishKiswahili = (subject1Code == ENGLISH_CODE && subject2Code == KISWAHILI_CODE) ||
                                                      (subject1Code == KISWAHILI_CODE && subject2Code == ENGLISH_CODE);
                              }
                              
                              if (isEnglishKiswahili) {
                                  // Create penalty variable for this pair
                                  BoolVar isAdjacent = model.newBoolVar(
                                      String.format("engkis_adjacent_%d_%d_day%d_slot%d", 
                                          lesson1.getId(), lesson2.getId(), day, i));
                                  
                                  // Get lesson slot variables
                                  IntVar slot1 = lessonSlots.get(lesson1);
                                  IntVar slot2 = lessonSlots.get(lesson2);
                                  
                                  // Add constraint: isAdjacent is true if lessons are in consecutive slots
                                  model.addEquality(slot1, currentSlot).onlyEnforceIf(isAdjacent);
                                  model.addEquality(slot2, nextSlot).onlyEnforceIf(isAdjacent);
                                  
                                  englishKiswahiliPenaltyVars.add(isAdjacent);
                              }
                          }
                      }
                  }
              }
          }
          
          System.out.println("Added English-Kiswahili non-adjacency constraint with " + 
              englishKiswahiliPenaltyVars.size() + " penalty variables");
      }


   private void implementTeacherConsecutiveLessonsAcrossStreams() {
          teacherConsecutiveLessonsAcrossAllStreamsPenaltyVars = new ArrayList<>();
          
          // Group lessons by teacher and day
          Map<Teacher, Map<Integer, List<Lesson>>> lessonsByTeacherAndDay = new HashMap<>();
          for (Lesson lesson : lessons) {
              for (Teacher teacher : lesson.getLessonInfo().getTeachers()) {
                  lessonsByTeacherAndDay.computeIfAbsent(teacher, k -> new HashMap<>());
              }
          }
          
          // For each teacher
          for (Teacher teacher : lessonsByTeacherAndDay.keySet()) {
              // For each day
              for (int day = 0; day < D; day++) {
                  final int currentDay = day;
                  // Get valid slots for this day
                  List<Integer> daySlots = validClassSlots.stream()
                      .filter(slot -> slot / T == currentDay)
                      .collect(Collectors.toList());
                  
                  // For each slot in the day (except last two to check three consecutive slots)
                  for (int i = 0; i < daySlots.size() - 2; i++) {
                      int currentSlot = daySlots.get(i);
                      int nextSlot = daySlots.get(i + 1);
                      int thirdSlot = daySlots.get(i + 2);
                      
                      // Create variables to track if teacher is teaching in each slot
                      BoolVar teachingInSlot1 = model.newBoolVar(
                          String.format("teacher_%d_teaching_day%d_slot%d", teacher.getId(), day, i));
                      BoolVar teachingInSlot2 = model.newBoolVar(
                          String.format("teacher_%d_teaching_day%d_slot%d", teacher.getId(), day, i + 1));
                      BoolVar teachingInSlot3 = model.newBoolVar(
                          String.format("teacher_%d_teaching_day%d_slot%d", teacher.getId(), day, i + 2));
                      
                      // Create variables to track if these are double lessons
                      BoolVar isDoubleInSlot1 = model.newBoolVar(
                          String.format("teacher_%d_double_day%d_slot%d", teacher.getId(), day, i));
                      BoolVar isDoubleInSlot2 = model.newBoolVar(
                          String.format("teacher_%d_double_day%d_slot%d", teacher.getId(), day, i + 1));
                      
                      // For all lessons
                      for (Lesson lesson : lessons) {
                          if (lesson.getLessonInfo().getTeachers().contains(teacher)) {
                              IntVar lessonSlot = lessonSlots.get(lesson);
                              boolean isDouble = lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                              
                              // Check if lesson starts in any of the slots
                              model.addEquality(lessonSlot, currentSlot).onlyEnforceIf(teachingInSlot1);
                              model.addEquality(lessonSlot, nextSlot).onlyEnforceIf(teachingInSlot2);
                              model.addEquality(lessonSlot, thirdSlot).onlyEnforceIf(teachingInSlot3);
                              
                              if (isDouble) {
                                  // For double lessons, also track the second slot
                                  IntVar secondSlot = doubleLessonSecondSlots.get(lesson);
                                  
                                  // If lesson starts in slot1, it's a double lesson in slot1
                                  model.addEquality(lessonSlot, currentSlot).onlyEnforceIf(isDoubleInSlot1);
                                  
                                  // If lesson starts in slot2, it's a double lesson in slot2
                                  model.addEquality(lessonSlot, nextSlot).onlyEnforceIf(isDoubleInSlot2);
                              }
                          }
                      }
                      
                      // Create penalty variable for overloaded sequence
                      BoolVar isOverloaded = model.newBoolVar(
                          String.format("teacher_%d_overloaded_day%d_slot%d", teacher.getId(), day, i));
                      
                      // Case 1: Double + Single
                      // [Double][Double's second][Single][No lesson]
                      model.addBoolAnd(new Literal[]{
                          teachingInSlot1,
                          isDoubleInSlot1,
                          teachingInSlot3
                      }).onlyEnforceIf(isOverloaded);
                      
                      // Case 2: Single + Double
                      // [Single][Double][Double's second][No lesson]
                      model.addBoolAnd(new Literal[]{
                          teachingInSlot1,
                          teachingInSlot2.not(),
                          isDoubleInSlot2,
                          teachingInSlot3
                      }).onlyEnforceIf(isOverloaded);
                      
                      // Case 3: Single + Single + Any lesson
                      // [Single][Single][Any lesson]
                      model.addBoolAnd(new Literal[]{
                          teachingInSlot1,
                          teachingInSlot2,
                          isDoubleInSlot1.not(),
                          isDoubleInSlot2.not(),
                          teachingInSlot3
                      }).onlyEnforceIf(isOverloaded);
                      
                      teacherConsecutiveLessonsAcrossAllStreamsPenaltyVars.add(isOverloaded);
                  }
              }
          }
          
          System.out.println("Added teacher consecutive lessons across streams constraint with " + 
              teacherConsecutiveLessonsAcrossAllStreamsPenaltyVars.size() + " penalty variables");
      }

   private void implementTeacherConsecutiveLessonsSameStream() {
        teacherConsecutiveLessonSameStreamPenaltyVars = new ArrayList<>();
        
        // Group lessons by stream
        Map<Stream, List<Lesson>> lessonsByStream = lessons.stream()
            .collect(Collectors.groupingBy(lesson -> lesson.getLessonInfo().getStream()));
            
        for (Map.Entry<Stream, List<Lesson>> entry : lessonsByStream.entrySet()) {
            Stream stream = entry.getKey();
            List<Lesson> streamLessons = entry.getValue();
            
            // For each day
            for (int day = 0; day < D; day++) {
                final int currentDay = day;
                // Get valid slots for this day
                List<Integer> daySlots = validClassSlots.stream()
                    .filter(slot -> slot / T == currentDay)
                    .collect(Collectors.toList());
                    
                // For each slot in the day (except last)
                for (int i = 0; i < daySlots.size() - 1; i++) {
                    int currentSlot = daySlots.get(i);
                    int nextSlot = daySlots.get(i + 1);
                    
                    // For each pair of lessons in this stream
                    for (Lesson lesson1 : streamLessons) {
                        for (Lesson lesson2 : streamLessons) {
                            if (lesson1 == lesson2) continue;
                            
                            // Check if lessons share any teachers
                            Set<Teacher> teachers1 = new HashSet<>(lesson1.getLessonInfo().getTeachers());
                            Set<Teacher> teachers2 = new HashSet<>(lesson2.getLessonInfo().getTeachers());
                            
                            Set<Teacher> commonTeachers = new HashSet<>(teachers1);
                            commonTeachers.retainAll(teachers2);
                            
                            if (!commonTeachers.isEmpty()) {
                                // Create penalty variable for consecutive lessons by same teacher
                                BoolVar isConsecutive = model.newBoolVar(
                                    String.format("teacher_consecutive_%d_%d_day%d_slot%d", 
                                        lesson1.getId(), lesson2.getId(), day, i));
                                
                                // Get lesson slot variables
                                IntVar slot1 = lessonSlots.get(lesson1);
                                IntVar slot2 = lessonSlots.get(lesson2);
                                
                                // Handle double lessons
                                boolean isDouble1 = lesson1.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                                boolean isDouble2 = lesson2.getLessonInfo().getLessonType() == LessonType.DOUBLE;
                                
                                if (isDouble1) {
                                    // If lesson1 is double, check if lesson2 starts right after lesson1's second slot
                                    IntVar secondSlot1 = doubleLessonSecondSlots.get(lesson1);
                                    model.addEquality(slot1, currentSlot).onlyEnforceIf(isConsecutive);
                                    model.addEquality(secondSlot1, nextSlot - 1).onlyEnforceIf(isConsecutive);
                                    model.addEquality(slot2, nextSlot).onlyEnforceIf(isConsecutive);
                                } else if (isDouble2) {
                                    // If lesson2 is double, check if it starts right after lesson1
                                    model.addEquality(slot1, currentSlot).onlyEnforceIf(isConsecutive);
                                    model.addEquality(slot2, nextSlot).onlyEnforceIf(isConsecutive);
                                } else {
                                    // Both are single lessons
                                    model.addEquality(slot1, currentSlot).onlyEnforceIf(isConsecutive);
                                    model.addEquality(slot2, nextSlot).onlyEnforceIf(isConsecutive);
                                }
                                
                                teacherConsecutiveLessonSameStreamPenaltyVars.add(isConsecutive);
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("Added teacher consecutive lessons in same stream constraint with " + 
            teacherConsecutiveLessonSameStreamPenaltyVars.size() + " penalty variables");
    }

  

   private void discourageScienceDoubleFirstMondayLesson() {
          scienceDoubleFirstMondayLessonPenaltyVars = new ArrayList<>();
          
          // For each lesson
          for (Lesson lesson : lessons) {
              // Check if it's a double science lesson
              if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE &&
                  lesson.getLessonInfo().getSubject() != null &&
                  SCIENCES_CATEGORY.equals(lesson.getLessonInfo().getSubject().getCategory().getName())) {
                  
                  // Get the decision variable for this lesson
                  IntVar lessonSlot = lessonSlots.get(lesson);
                  
                  // Create a Boolean variable that is true if the lesson is in the first Monday slot
                  BoolVar isScheduledFirstMonday = model.newBoolVar(
                      String.format("science_double_first_monday_%d", lesson.getId()));
                  
                  // First slot of Monday is slot 0
                  model.addEquality(lessonSlot, 0).onlyEnforceIf(isScheduledFirstMonday);
                  model.addDifferent(lessonSlot, 0).onlyEnforceIf(isScheduledFirstMonday.not());
                  
                  scienceDoubleFirstMondayLessonPenaltyVars.add(isScheduledFirstMonday);
              }
          }
          
          System.out.println("Added science double first Monday lesson constraint with " + 
              scienceDoubleFirstMondayLessonPenaltyVars.size() + " penalty variables");
      }

   private void discourageScienceDoubleAfternoonLessons() {
          discourageScienceDoubleAfternoonLesson = new ArrayList<>();
          
          // For each lesson
          for (Lesson lesson : lessons) {
              // Check if it's a double science lesson
              if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE &&
                  lesson.getLessonInfo().getSubject() != null &&
                  SCIENCES_CATEGORY.equals(lesson.getLessonInfo().getSubject().getCategory().getName())) {
                  
                  // Get the decision variable for this lesson
                  IntVar lessonSlot = lessonSlots.get(lesson);
                  
                  // For each day
                  for (int day = 0; day < D; day++) {
                      // Find afternoon slots for this day
                      List<Integer> afternoonSlots = new ArrayList<>();
                      for (int slot = 0; slot < T - 1; slot++) { // -1 because double lessons need two slots
                          int globalSlot = day * T + slot;
                          
                          // Check if this is an afternoon slot and the next slot is valid for a double lesson
                          if (validClassSlots.contains(globalSlot) && 
                              validClassSlots.contains(globalSlot + 1)) {
                              
                              ScheduleTimeslot currentTimeslot = schedule.getTimeslotList().get(slot);
                              if (currentTimeslot.getTimeslotActivity() == TimeslotActivity.CLASS &&
                                  currentTimeslot.getTimeOfDay() == TimeOfDay.AFTERNOON) {
                                  afternoonSlots.add(globalSlot);
                              }
                          }
                      }
                      
                      // For each afternoon slot
                      for (int afternoonSlot : afternoonSlots) {
                          // Create a penalty variable if the lesson is scheduled in this afternoon slot
                          BoolVar isScheduledInAfternoon = model.newBoolVar(
                              String.format("science_double_afternoon_d%d_l%d_s%d", 
                                  day, lesson.getId(), afternoonSlot));
                          
                          model.addEquality(lessonSlot, afternoonSlot).onlyEnforceIf(isScheduledInAfternoon);
                          model.addDifferent(lessonSlot, afternoonSlot).onlyEnforceIf(isScheduledInAfternoon.not());
                          
                          // Add weighted penalty
                          IntVar weightedPenalty = model.newIntVar(0, 15, 
                              String.format("weighted_science_double_afternoon_d%d_l%d_s%d", 
                                  day, lesson.getId(), afternoonSlot));
                          model.addEquality(weightedPenalty, LinearExpr.term(isScheduledInAfternoon, 15));
                          
                          discourageScienceDoubleAfternoonLesson.add(weightedPenalty);
                      }
                  }
              }
          }
          
          System.out.println("Added science double afternoon lesson constraint with " + 
              discourageScienceDoubleAfternoonLesson.size() + " penalty variables");
      }
  
  private volatile boolean shouldStop = false;
  private volatile CpSolverStatus bestStatus = null;
  private volatile Map<Lesson, Integer> bestSolution = null;

  public interface SolverCallback {
      void onPhase1Complete(CpSolverStatus status, Map<Lesson, Integer> solution);
      void onBetterSolutionFound(CpSolverStatus status, Map<Lesson, Integer> solution);
      void onError(String errorMessage);
  }

  private SolverCallback callback;

  public void setCallback(SolverCallback callback) {
      this.callback = callback;
  }

  private void displayCurrentSolution() {
      synchronized (this) {
          if (bestSolution == null) {
              System.out.println("No solution available to display.");
              return;
          }
          
          System.out.println("\nCurrent Timetable Solution:");
          System.out.println("------------------------");
          
          try {
              // Create a temporary map to store the current solver values
              Map<Lesson, IntVar> originalSlots = new HashMap<>(lessonSlots);
              
              // Temporarily set the solver values to the best solution
              for (Map.Entry<Lesson, Integer> entry : bestSolution.entrySet()) {
                  lessonSlots.put(entry.getKey(), model.newConstant(entry.getValue()));
              }
              
              // Print the solution
              printSolution();
              
              // Restore the original solver values
              lessonSlots.clear();
              lessonSlots.putAll(originalSlots);
          } catch (Exception e) {
              System.err.println("Error displaying solution: " + e.getMessage());
              e.printStackTrace();
          }
        }
    }

    private void printSolution() {
    // Debug information
    System.out.println("\nDebug Information:");
    System.out.println("-----------------");
    System.out.println("Total timeslots in schedule: " + schedule.getTimeslotList().size());
    System.out.println("Timeslots per day (T): " + T);
    System.out.println("Total days (D): " + D);
    System.out.println("Maximum possible slot index: " + (D * T - 1));
    System.out.println("Number of lessons to schedule: " + lessons.size());
    System.out.println("Valid class slots count: " + validClassSlots.size());
    System.out.println("Valid class slots range: " + 
        (validClassSlots.isEmpty() ? "empty" : 
         "[" + validClassSlots.get(0) + " to " + validClassSlots.get(validClassSlots.size() - 1) + "]"));

    // Track invalid slots
    int invalidSlots = 0;
    Map<Integer, Integer> slotFrequency = new HashMap<>();

    // Print the solution
        for (Lesson lesson : lessons) {
        int slot = bestSolution.get(lesson);
        int dayOfWeek = slot / T;
            int timeslot = slot % T;
            
        // Check if this is a valid slot
        if (!validClassSlots.contains(slot)) {
            invalidSlots++;
            continue;
        }
        
        // Update slot frequency
        slotFrequency.merge(slot, 1, Integer::sum);

          // Get lesson details
        long streamId = lesson.getLessonInfo().getStream().getId();
          Long subjectId = lesson.getLessonInfo().getSubject() != null ? 
              lesson.getLessonInfo().getSubject().getId() : 
              lesson.getLessonInfo().getCombinedSubject().getId();
          String teacherIds = lesson.getLessonInfo().getTeachers().stream()
              .map(teacher -> teacher.getId().toString())
              .collect(Collectors.joining(", "));

        // Print the lesson
          System.out.println(String.format(
              "Day %d, Timeslot %d | Lesson ID: %d | Stream ID: %d | Subject ID: %d | Teacher IDs: [%s] | Type: %s",
            dayOfWeek + 1, // Convert to 1-based day
              timeslot + 1, // Convert to 1-based timeslot
                lesson.getId(),
            streamId,
            subjectId,
            teacherIds,
                lesson.getLessonInfo().getLessonType()
            ));

        // If this is a double lesson, print the second slot
        if (lesson.getLessonInfo().getLessonType() == LessonType.DOUBLE) {
            int secondSlot = slot + 1;
            if (validClassSlots.contains(secondSlot)) {
                // Update slot frequency for second slot
                slotFrequency.merge(secondSlot, 1, Integer::sum);
                
                System.out.println(String.format(
                    "Day %d, Timeslot %d | Lesson ID: %d | Stream ID: %d | Subject ID: %d | Teacher IDs: [%s] | Type: %s (Second slot)",
                    dayOfWeek + 1, // Convert to 1-based day
                    (secondSlot % T) + 1, // Convert to 1-based timeslot
              lesson.getId(),
              streamId,
              subjectId,
              teacherIds,
              lesson.getLessonInfo().getLessonType()
          ));
        } else {
                invalidSlots++;
            }
        }
    }

    // Print summary
    System.out.println("\nSolution Summary:");
    System.out.println("----------------");
    System.out.println("Total lessons: " + lessons.size());
    System.out.println("Invalid slots: " + invalidSlots);
    System.out.println("Unique slots used: " + slotFrequency.size());
    
    // Find most frequent slots
    System.out.println("\nMost frequent slots:");
    slotFrequency.entrySet().stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
        .limit(5)
        .forEach(entry -> System.out.println("Slot " + entry.getKey() + ": " + entry.getValue() + " lessons"));
    }

    public void solve() {
      System.out.println("\nStarting Phase 1: Core Constraints");
      System.out.println("---------------------------------");
      
      // Phase 1: Core Constraints
      createDecisionVariables();
      enforceLessonNonOverlapConstraints();
      enforceDoubleLessonConstraints();
      oneLessonPerTimeslotPerStreamPerDay();
      applyCombinedSubjectsConstraint();
      applyPhase1Constraints();
      
      // Try to solve with Phase 1 constraints
      CpSolverStatus phase1Status = solver.solve(model);
      
      if (phase1Status != CpSolverStatus.OPTIMAL && phase1Status != CpSolverStatus.FEASIBLE) {
          System.out.println("\nPhase 1 failed: No solution found with core constraints");
          System.out.println("Analyzing conflicts...");
          if (callback != null) {
              callback.onError("Phase 1 failed: No solution found with core constraints");
          }
          return;
      }
      
      // Store the first feasible solution
      bestStatus = phase1Status;
      bestSolution = new HashMap<>();
      for (Lesson lesson : lessons) {
          bestSolution.put(lesson, (int) solver.value(lessonSlots.get(lesson)));
      }
      
      
      // Notify callback about Phase 1 completion
      if (callback != null) {
          callback.onPhase1Complete(phase1Status, bestSolution);
      }
      
      // Display the initial solution
      System.out.println("\nFound initial feasible solution. Displaying...");
      displayCurrentSolution();
      
      // Start background optimization
      Thread optimizationThread = new Thread(() -> {
          try {
              System.out.println("\nStarting Phase 2: Additional Hard Constraints");
              System.out.println("--------------------------------------------");
              
              // Phase 2: Additional Hard Constraints
              applyPhase2Constraints();
              
              // Try to solve with Phase 2 constraints
              CpSolverStatus phase2Status = solver.solve(model);
              
              if (phase2Status == CpSolverStatus.OPTIMAL || phase2Status == CpSolverStatus.FEASIBLE) {
                  synchronized (this) {
                      bestStatus = phase2Status;
                      bestSolution.clear();
                      for (Lesson lesson : lessons) {
                          bestSolution.put(lesson, (int) solver.value(lessonSlots.get(lesson)));
                      }
                  }
                 
                  // Notify callback about better solution
                  if (callback != null) {
                      callback.onBetterSolutionFound(phase2Status, bestSolution);
                  }
                  System.out.println("\nFound better solution with Phase 2 constraints. Updating display...");
                  displayCurrentSolution();
              } else {
                  System.out.println("\nPhase 2 did not find a better solution. Keeping previous solution.");
              }
              
              System.out.println("\nStarting Phase 3: Soft Constraints");
              System.out.println("---------------------------------");
              
              // Phase 3: Soft Constraints
              applyPhase3Constraints();
              
              // Try to solve with all constraints
              CpSolverStatus finalStatus = solver.solve(model);
              
              if (finalStatus == CpSolverStatus.OPTIMAL || finalStatus == CpSolverStatus.FEASIBLE) {
                  synchronized (this) {
                      bestStatus = finalStatus;
                      bestSolution.clear();
                      for (Lesson lesson : lessons) {
                          bestSolution.put(lesson, (int) solver.value(lessonSlots.get(lesson)));
                      }
                  }
                  // Notify callback about better solution
                  if (callback != null) {
                      callback.onBetterSolutionFound(finalStatus, bestSolution);
                  }
                  System.out.println("\nFound optimal solution. Updating display...");
                  displayCurrentSolution();
                } else {
                  System.out.println("\nPhase 3 did not find a better solution. Keeping previous solution.");
            }

          } catch (Exception e) {
              System.err.println("Error during optimization: " + e.getMessage());
              e.printStackTrace();
              if (callback != null) {
                  callback.onError("Error during optimization: " + e.getMessage());
              }
          }
      });
      
      optimizationThread.start();
    }

    private void applyPhase1Constraints() {
        System.out.println("Applying Phase 1 constraints:");
        // Core constraints that must be satisfied
        if (constraintMethods.containsKey("teacher_conflict")) {
            System.out.println("- Teacher conflict constraints");
            constraintMethods.get("teacher_conflict").run();
        }
        if (constraintMethods.containsKey("teacher_unavailability")) {
            System.out.println("- Teacher unavailability constraints");
            constraintMethods.get("teacher_unavailability").run();
        }
        if (constraintMethods.containsKey("lesson_distribution")) {
            System.out.println("- Lesson distribution constraints");
            constraintMethods.get("lesson_distribution").run();
        }
        if (constraintMethods.containsKey("better_lesson_distribution")) {
            System.out.println("- Better lesson distribution constraints");
            constraintMethods.get("better_lesson_distribution").run();
        }
        if (constraintMethods.containsKey("combined_streams_to_be_taught_at_same_time")) {
            System.out.println("- Combined streams constraints");
            constraintMethods.get("combined_streams_to_be_taught_at_same_time").run();
        }
      }

    private void applyPhase2Constraints() {
        System.out.println("Applying Phase 2 constraints:");
        // Additional hard constraints
        if (constraintMethods.containsKey("hard_subjects_taught_at_most_twice_per_day")) {
            System.out.println("- Core subjects per day constraints");
            constraintMethods.get("hard_subjects_taught_at_most_twice_per_day").run();
        }
    }

    private void applyPhase3Constraints() {
        System.out.println("Applying Phase 3 constraints:");
        // Soft constraints for optimization
        if (constraintMethods.containsKey("pe_lessons_near_breaks")) {
            System.out.println("- PE lesson placement constraints");
            constraintMethods.get("pe_lessons_near_breaks").run();
        }
        if (constraintMethods.containsKey("math_science_non_adjacency")) {
            System.out.println("- Math-Science non-adjacency constraints");
            constraintMethods.get("math_science_non_adjacency").run();
        }
        if (constraintMethods.containsKey("english_kiswahili_non_adjacency")) {
            System.out.println("- English-Kiswahili non-adjacency constraints");
            constraintMethods.get("english_kiswahili_non_adjacency").run();
        }
        if (constraintMethods.containsKey("teacher_consecutive_lessons_across_streams")) {
            System.out.println("- Teacher consecutive lessons constraints");
            constraintMethods.get("teacher_consecutive_lessons_across_streams").run();
        }
        if (constraintMethods.containsKey("science_double_afternoon_lessons")) {
            System.out.println("- Science double lesson placement constraints");
            constraintMethods.get("science_lab_lessons_monday_morning").run();
        }
        if (constraintMethods.containsKey("science_double_lessons_afternoon")) {
            System.out.println("- Science afternoon double lesson constraints");
            constraintMethods.get("science_double_lessons_afternoon").run();
        }
    }
} 