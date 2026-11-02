# GPS gap reconstruction — synthetic walking evaluation

## Method

- A synthetic 4,000-meter walking trajectory with multiple 90-degree turns, speed ~1.33 m/s, and samples nominally every five seconds.
- Nine fixed outages lasting about 25–90 seconds, plus 12% randomly missing observations.
- GPS uncertainty drawn from 7, 12, 18, 26, 32, 44, 65 and 100 meters, with noise scaled to the reported accuracy; 1.4% occasional large offset spikes.
- 20 seeded runs (seeds 10–29), same simulated observations for original and revised algorithms.
- Walk coverage estimated by sampling the ground-truth path at ten-meter intervals, counting points inside 20-meter buffered saved fixes or joinable segments.
- Extraneous reveal estimated as percentage of exposed area outside a 30-meter corridor around the ground-truth route.
- The original algorithm used the former walking save threshold and receipt-time batching. The revised variant used tighter sampling, short-lived accuracy-weighted smoothing, measurement-time timestamps, walking speed plausibility, and direction-confirmed gap joins.

| Metric (mean of 20 runs) | Old | Revised |
| --- | ---: | ---: |
| Saved walking fixes | 113.9 | 223.5 |
| Segments connected | 100.5 | 205.0 |
| True walk covered by fog reveal | 87.8% | 87.6% |
| Revealed area outside route buffer | 6.6% | 1.8% |
| Joined path length more than 35 m from true route | 149.6 m | 0.3 m |

## Interpretation and limitations

The new algorithm records more closely spaced observations while drastically reducing false trails in this particular synthetic environment. It **does not** increase total walk coverage under long GPS blackouts. The system deliberately leaves long gaps unfilled rather than claiming unknown streets were visited.

These measurements are **not from a real Dubai Marina GPS log** and are not a guarantee of real-world performance. Actual testing should record accepted and rejected fix quality, timestamps, and enough privacy-protected diagnostics to distinguish provider loss from policy rejection. Never infer a street route across a long unobserved period without additional trusted motion or route evidence.

The radius remains 20 meters. Existing exploration data is preserved without modifying legacy timestamps.
