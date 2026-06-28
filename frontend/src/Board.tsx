// The 8x8 Othello board, rendered purely from the server's `cells` string. Squares the server
// marked legal are highlighted and clickable; everything else is presentational. No game logic
// here — a click just reports the square index up to the caller.
//
// Discs animate on change: a freshly placed disc pops in, and a captured disc does a 3D flip from
// its old colour to its new one. We detect what changed by diffing the previous `cells` against the
// current one (kept in a ref). In the M4 slice the human move and the synchronous bot reply arrive
// in one state update, so everything animates together; M8's WebSocket split will let us stage them.

import { useEffect, useRef } from 'react';
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

  // Previous board, for change detection. Updated after paint so this render sees the old value.
  const prevRef = useRef(cells);
  const prev = prevRef.current;
  useEffect(() => {
    prevRef.current = cells;
  }, [cells]);

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
                {cell !== '.' && renderDisc(square, cell, prev[square])}
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

/** A disc, animated by how it changed from `before`: flipped (was the other colour) or just placed. */
function renderDisc(square: number, cell: string, before: string) {
  const color = cell === 'B' ? 'black' : 'white';

  if (before !== '.' && before !== cell) {
    // Captured: 3D flip from the old colour (front) to the new one (back). A changing key remounts
    // the element so the flip animation replays every time this square is captured again.
    const from = before === 'B' ? 'black' : 'white';
    return (
      <span className="flipper" key={`${square}-flip-${cell}`}>
        <span className={`disc face disc-${from}`} />
        <span className={`disc face face-back disc-${color}`} />
      </span>
    );
  }
  if (before === '.') {
    // Freshly placed onto an empty square: pop in.
    return <span className={`disc disc-${color} disc-pop`} key={`${square}-place-${cell}`} />;
  }
  return <span className={`disc disc-${color}`} />;
}
