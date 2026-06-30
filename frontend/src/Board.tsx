// The 8x8 Othello board, rendered purely from the server's `cells` string. Squares the server
// marked legal are highlighted and clickable; everything else is presentational. No game logic
// here — a click just reports the square index up to the caller.
//
// Discs animate on change: a freshly placed disc pops in, and a captured disc does a 3D flip from
// its old colour to its new one. We detect what changed by diffing the previous `cells` against the
// current one (kept in a ref). Each animation needs a painted frame plus its duration to be seen, so
// GameView staggers the human move and the bot's WebSocket reply (STAGE_MS) rather than letting a
// fast reply overwrite the human-move frame before it animates — see GameView's showState.

import { useRef } from 'react';
import type { Player } from './types';

interface BoardProps {
  cells: string;
  legalMoves: number[];
  // Whose turn it is, used only to tint the legal-move hints with the side about to play.
  turn: Player;
  interactive: boolean;
  onPlay: (square: number) => void;
}

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

export default function Board({
  cells,
  legalMoves,
  turn,
  interactive,
  onPlay,
}: BoardProps) {
  const legal = new Set(legalMoves);

  // Previous board, for the flip/pop change detection. We hold the *pre-change* board and only
  // advance it when `cells` actually changes — not on every render. So a captured disc keeps
  // rendering as the same <flipper> element (stable key) across re-renders that *don't* change the
  // board — e.g. the parent toggling `busy`/`staging` mid-flip — and React preserves the in-flight
  // CSS animation instead of swapping in a static disc and cutting it short. (Updating prev in an
  // effect instead would make any such re-render drop the animation early.)
  const prevRef = useRef(cells);
  const curRef = useRef(cells);
  if (cells !== curRef.current) {
    prevRef.current = curRef.current;
    curRef.current = cells;
  }
  const prev = prevRef.current;

  // Squares where a disc was just placed (empty -> disc). Captures cascade outward from these.
  const placed: number[] = [];
  for (let i = 0; i < 64; i++) {
    if (prev[i] === '.' && cells[i] !== '.') placed.push(i);
  }

  // Standard orientation: rank 8 on top, file a on the left. Square index is row*8+col with a1=0.
  const ranks = [7, 6, 5, 4, 3, 2, 1, 0];

  return (
    <div className="board-frame">
      <div className="board">
        {ranks.map((row) =>
          FILES.map((file, col) => {
            const square = row * 8 + col;
            const cell = cells[square];
            const playable = interactive && legal.has(square);
            return (
              <button
                key={square}
                type="button"
                className="square"
                aria-label={`${file}${row + 1}`}
                data-square={square}
                disabled={!playable}
                onClick={() => onPlay(square)}
              >
                {cell !== '.' && renderDisc(square, cell, prev[square], flipDelay(square, placed))}
                {cell === '.' && playable && (
                  <span
                    className={`hint hint-${turn.toLowerCase()}`}
                    aria-hidden="true"
                  />
                )}
              </button>
            );
          }),
        )}
      </div>
    </div>
  );
}

const CASCADE_STEP_MS = 70;

/**
 * Stagger a captured disc's flip by its distance from the nearest just-placed disc, so a captured
 * line ripples outward from the move rather than flipping all at once. Discs hold their old colour
 * until their flip fires (the animation's pre-delay state), which is what makes the sweep read.
 */
function flipDelay(square: number, placed: number[]): number {
  if (placed.length === 0) return 0;
  const r = Math.floor(square / 8);
  const c = square % 8;
  let nearest = Infinity;
  for (const p of placed) {
    const chebyshev = Math.max(Math.abs(r - Math.floor(p / 8)), Math.abs(c - (p % 8)));
    nearest = Math.min(nearest, chebyshev);
  }
  return Math.max(0, nearest - 1) * CASCADE_STEP_MS;
}

/** A disc, animated by how it changed from `before`: flipped (was the other colour) or just placed. */
function renderDisc(square: number, cell: string, before: string, delayMs: number) {
  const color = cell === 'B' ? 'black' : 'white';

  if (before !== '.' && before !== cell) {
    // Captured: 3D flip from the old colour (front) to the new one (back). A changing key remounts
    // the element so the flip animation replays every time this square is captured again. The delay
    // staggers the flip for the outward cascade.
    //
    // The drop shadow lives on a separate, non-rotating layer behind the flipper rather than on the
    // rotating faces. A box-shadow on a face only paints while that face is front-facing, so a disc
    // waiting its turn in a cascade (holding its old colour, edge-up, before its delay elapses) would
    // otherwise look flat — its shadow vanishing until it flips. The static layer keeps the shadow in
    // every phase: waiting, flipping, and at rest.
    const from = before === 'B' ? 'black' : 'white';
    return (
      <span className="flip-wrap" key={`${square}-flip-${cell}`}>
        {/* The shadow layer squashes in lockstep with the flip (same delay), so its hollow circle
            collapses to a thin line as the disc turns edge-on instead of showing through behind it. */}
        <span className="flip-shadow" style={{ animationDelay: `${delayMs}ms` }} aria-hidden="true" />
        <span className="flipper" style={{ animationDelay: `${delayMs}ms` }}>
          <span className={`disc face disc-${from}`} />
          <span className={`disc face face-back disc-${color}`} />
        </span>
      </span>
    );
  }
  if (before === '.') {
    // Freshly placed onto an empty square: pop in.
    return <span className={`disc disc-${color} disc-pop`} key={`${square}-place-${cell}`} />;
  }
  return <span className={`disc disc-${color}`} />;
}
